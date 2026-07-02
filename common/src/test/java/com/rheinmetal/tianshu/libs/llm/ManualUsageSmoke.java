package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ManualUsageSmoke {
    public static void main(String[] args) throws Exception {
        String model = System.getProperty("tianshu.llm.model");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("System property tianshu.llm.model is required");
        }
        Path modelPath = Path.of(model);
        if (!Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("Model file not found: " + model);
        }

        int context = Integer.getInteger("tianshu.llm.context", 4096);
        int gpuLayers = Integer.getInteger("tianshu.llm.gpuLayers", 999);
        int maxTokens = Integer.getInteger("tianshu.llm.maxTokens", 32);
        int chatThreads = Integer.getInteger("tianshu.llm.chatThreads", 4);
        String profile = System.getProperty("tianshu.llm.modelProfile", "auto");
        String device = System.getProperty("tianshu.llm.device", "");
        String outputFile = System.getProperty("tianshu.llm.outputFile", "");
        Path outputPath = outputFile.isBlank() ? null : Path.of(outputFile).toAbsolutePath().normalize();
        if (outputPath != null && outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }

        SamplerConfig sampler = new SamplerConfig();
        sampler.setTemperature(0.0f);
        sampler.setEnableThinking(false);

        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are a concise smoke-test assistant. Do not reason step by step."),
                ChatMessage.user("Reply with exactly this text: TOKEN_USAGE_OK")
        );

        System.out.printf(
                "Manual usage smoke%nmodel=%s%ncontext=%d, gpuLayers=%d, chatThreads=%d, maxTokens=%d, device=%s%n",
                model, context, gpuLayers, chatThreads, maxTokens, device.isBlank() ? "<default>" : device
        );

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(model)
                .modelAlias(modelPath.getFileName().toString())
                .modelProfile(profile)
                .contextSize(context)
                .chatThreads(chatThreads)
                .chatMaxQueueSize(4)
                .taskThreads(2)
                .gpuLayers(gpuLayers)
                .requestTimeoutSeconds(300);
        if (!device.isBlank()) {
            builder.device(device);
        }

        JavaLlamaServer service = builder.build();
        PrintWriter output = null;
        try {
            service.start();
            output = outputPath == null
                    ? null
                    : new PrintWriter(Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8));
            writeOutput(output, "model", model);
            int countedPromptTokens = service.countChatPromptTokens(messages, sampler);
            System.out.println("countChatPromptTokens=" + countedPromptTokens);
            writeOutput(output, "countChatPromptTokens", Integer.toString(countedPromptTokens));

            LlmGenerationResult chat = service.chatWithUsage(messages, sampler, maxTokens)
                    .get(300, TimeUnit.SECONDS);
            assertUsage("chatWithUsage", countedPromptTokens, chat.usage());
            System.out.println("chatWithUsage.text=" + compact(chat.text()));
            printUsage("chatWithUsage", chat.usage());
            writeResult(output, "chatWithUsage", chat);

            AtomicReference<LlmStreamFinish> chatFinish = new AtomicReference<>();
            StringBuilder chatStreamText = new StringBuilder();
            service.chatStream(messages, sampler, maxTokens, null, chatStreamText::append, chatFinish::set)
                    .get(300, TimeUnit.SECONDS);
            assertFinish("chatStream", countedPromptTokens, chatFinish.get());
            System.out.println("chatStream.text=" + compact(chatStreamText.toString()));
            printUsage("chatStream", chatFinish.get().usage());
            writeStreamResult(output, "chatStream", chatStreamText.toString(), chatFinish.get());

            LlmGenerationResult task = service.taskWithUsage(messages, sampler, maxTokens, 0, false, null)
                    .get(300, TimeUnit.SECONDS);
            assertUsage("taskWithUsage", countedPromptTokens, task.usage());
            System.out.println("taskWithUsage.text=" + compact(task.text()));
            printUsage("taskWithUsage", task.usage());
            writeResult(output, "taskWithUsage", task);

            AtomicReference<LlmStreamFinish> taskFinish = new AtomicReference<>();
            StringBuilder taskStreamText = new StringBuilder();
            CompletableFuture<LlmGenerationResult> taskStream = service.taskStreamWithUsage(
                    messages,
                    sampler,
                    maxTokens,
                    0,
                    false,
                    null,
                    taskStreamText::append,
                    taskFinish::set
            );
            LlmGenerationResult taskStreamResult = taskStream.get(300, TimeUnit.SECONDS);
            assertUsage("taskStreamWithUsage", countedPromptTokens, taskStreamResult.usage());
            assertFinish("taskStreamWithUsage", countedPromptTokens, taskFinish.get());
            System.out.println("taskStreamWithUsage.text=" + compact(taskStreamText.toString()));
            printUsage("taskStreamWithUsage", taskStreamResult.usage());
            writeStreamResult(output, "taskStreamWithUsage", taskStreamText.toString(), taskFinish.get());

            runTaskInterruptedByChat(service, sampler, maxTokens);
            runChatCancellation(service, sampler);
            runTaskCancellation(service, sampler);
            runQueuedCancellation(service, sampler);
        } finally {
            if (output != null) {
                output.flush();
                output.close();
            }
            service.shutdown();
        }
    }

    private static void runTaskInterruptedByChat(JavaLlamaServer service, SamplerConfig sampler, int maxTokens) throws Exception {
        List<ChatMessage> taskMessages = List.of(
                ChatMessage.system("You are a concise smoke-test assistant. Do not reason step by step."),
                ChatMessage.user("Count from 1 to 80 separated by commas. Keep going until complete.")
        );
        int taskPromptTokens = service.countChatPromptTokens(taskMessages, sampler);
        CountDownLatch firstToken = new CountDownLatch(1);
        AtomicReference<LlmStreamFinish> taskFinish = new AtomicReference<>();
        StringBuilder taskText = new StringBuilder();
        CompletableFuture<LlmGenerationResult> task = service.taskStreamWithUsage(
                taskMessages,
                sampler,
                120,
                0,
                true,
                null,
                token -> {
                    taskText.append(token);
                    firstToken.countDown();
                },
                taskFinish::set
        );
        if (!firstToken.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("interrupt smoke task did not start streaming");
        }

        List<ChatMessage> chatMessages = List.of(ChatMessage.user("Reply with exactly: CHAT_INTERRUPT_OK"));
        int chatPromptTokens = service.countChatPromptTokens(chatMessages, sampler);
        LlmGenerationResult chat = service.chatWithUsage(chatMessages, sampler, maxTokens)
                .get(300, TimeUnit.SECONDS);
        assertUsage("interrupt.chatWithUsage", chatPromptTokens, chat.usage());

        LlmGenerationResult taskResult = task.get(300, TimeUnit.SECONDS);
        assertUsage("interrupt.taskStreamWithUsage", taskPromptTokens, taskResult.usage());
        assertFinish("interrupt.taskStreamWithUsage", taskPromptTokens, taskFinish.get());
        if (!taskResult.text().equals(taskText.toString())) {
            throw new IllegalStateException("interrupt task stream text does not match final result");
        }
        printUsage("interrupt.chatWithUsage", chat.usage());
        printUsage("interrupt.taskStreamWithUsage", taskResult.usage());
    }

    private static void runChatCancellation(JavaLlamaServer service, SamplerConfig sampler) throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are a concise smoke-test assistant. Do not reason step by step."),
                ChatMessage.user("Write 200 comma-separated integers starting from 1.")
        );
        CountDownLatch firstToken = new CountDownLatch(1);
        AtomicReference<LlmStreamFinish> finish = new AtomicReference<>();
        CompletableFuture<String> chat = service.chatStream(
                messages,
                sampler,
                240,
                null,
                token -> firstToken.countDown(),
                finish::set
        );
        if (!firstToken.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("cancel smoke chat did not start streaming");
        }
        if (!chat.cancel(false)) {
            throw new IllegalStateException("cancel smoke chat did not accept cancellation");
        }
        try {
            chat.get(30, TimeUnit.SECONDS);
            throw new IllegalStateException("cancelled chat unexpectedly completed normally");
        } catch (CancellationException expected) {
            // expected
        }
        LlmStreamFinish cancelled = awaitFinish("cancel.chatStream", finish, 30);
        if (cancelled.type() != StreamFinishType.CANCELLED) {
            throw new IllegalStateException("chat cancel finish type should be CANCELLED: " + cancelled.type());
        }
        System.out.println("cancel.chatStream.usage prompt="
                + cancelled.usage().promptTokens()
                + " completion=" + cancelled.usage().completionTokens()
                + " total=" + cancelled.usage().totalTokens());
    }

    private static void runTaskCancellation(JavaLlamaServer service, SamplerConfig sampler) throws Exception {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("You are a concise smoke-test assistant. Do not reason step by step."),
                ChatMessage.user("Write 200 comma-separated integers starting from 1.")
        );
        CountDownLatch firstToken = new CountDownLatch(1);
        AtomicReference<LlmStreamFinish> finish = new AtomicReference<>();
        CompletableFuture<LlmGenerationResult> task = service.taskStreamWithUsage(
                messages,
                sampler,
                240,
                0,
                true,
                null,
                token -> firstToken.countDown(),
                finish::set
        );
        if (!firstToken.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("cancel smoke task did not start streaming");
        }
        if (!task.cancel(false)) {
            throw new IllegalStateException("cancel smoke task did not accept cancellation");
        }
        try {
            task.get(30, TimeUnit.SECONDS);
            throw new IllegalStateException("cancelled task unexpectedly completed normally");
        } catch (CancellationException expected) {
            // expected
        }
        LlmStreamFinish cancelled = awaitFinish("cancel.taskStreamWithUsage", finish, 30);
        if (cancelled.type() != StreamFinishType.CANCELLED) {
            throw new IllegalStateException("cancel finish type should be CANCELLED: " + cancelled.type());
        }
        System.out.println("cancel.taskStreamWithUsage.usage prompt="
                + cancelled.usage().promptTokens()
                + " completion=" + cancelled.usage().completionTokens()
                + " total=" + cancelled.usage().totalTokens());
    }

    private static void runQueuedCancellation(JavaLlamaServer service, SamplerConfig sampler) throws Exception {
        List<ChatMessage> longMessages = List.of(
                ChatMessage.system("You are a concise smoke-test assistant. Do not reason step by step."),
                ChatMessage.user("Write 240 comma-separated integers starting from 1.")
        );
        CountDownLatch firstStarted = new CountDownLatch(1);
        CompletableFuture<LlmGenerationResult> first = service.taskStreamWithUsage(
                longMessages,
                sampler,
                260,
                0,
                false,
                null,
                token -> firstStarted.countDown(),
                ignored -> {
                }
        );
        if (!firstStarted.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("queued cancel first task did not start");
        }

        AtomicReference<LlmStreamFinish> queuedFinish = new AtomicReference<>();
        CompletableFuture<LlmGenerationResult> queued = service.taskStreamWithUsage(
                List.of(ChatMessage.user("Reply with exactly: SHOULD_NOT_RUN")),
                sampler,
                32,
                0,
                false,
                null,
                ignored -> {
                    throw new IllegalStateException("queued cancelled task should not stream tokens");
                },
                queuedFinish::set
        );
        if (!queued.cancel(false)) {
            throw new IllegalStateException("queued task did not accept cancellation");
        }
        try {
            queued.get(30, TimeUnit.SECONDS);
            throw new IllegalStateException("queued cancelled task unexpectedly completed normally");
        } catch (CancellationException expected) {
            // expected
        }
        LlmStreamFinish finish = awaitFinish("queued.cancel", queuedFinish, 30);
        if (finish.type() != StreamFinishType.CANCELLED) {
            throw new IllegalStateException("queued cancel finish type should be CANCELLED: " + finish.type());
        }
        first.cancel(false);
        try {
            first.get(30, TimeUnit.SECONDS);
        } catch (CancellationException expected) {
            // expected cleanup
        }
        System.out.println("queued.cancel.usage prompt="
                + finish.usage().promptTokens()
                + " completion=" + finish.usage().completionTokens()
                + " total=" + finish.usage().totalTokens());
    }

    private static void assertUsage(String label, int expectedPromptTokens, LlmTokenUsage usage) {
        if (usage == null) {
            throw new IllegalStateException(label + " usage is null");
        }
        if (usage.promptTokens() != expectedPromptTokens) {
            throw new IllegalStateException(label + " promptTokens mismatch: expected="
                    + expectedPromptTokens + ", actual=" + usage.promptTokens());
        }
        if (usage.completionTokens() <= 0) {
            throw new IllegalStateException(label + " completionTokens should be positive: " + usage.completionTokens());
        }
        if (usage.totalTokens() != usage.promptTokens() + usage.completionTokens()) {
            throw new IllegalStateException(label + " totalTokens mismatch");
        }
    }

    private static void assertFinish(String label, int expectedPromptTokens, LlmStreamFinish finish) {
        if (finish == null) {
            throw new IllegalStateException(label + " finish is null");
        }
        if (finish.type() != StreamFinishType.COMPLETED) {
            throw new IllegalStateException(label + " finish type should be COMPLETED: " + finish.type());
        }
        if (finish.error() != null) {
            throw new IllegalStateException(label + " finish error should be null", finish.error());
        }
        assertUsage(label + ".finish", expectedPromptTokens, finish.usage());
    }

    private static LlmStreamFinish awaitFinish(String label,
                                               AtomicReference<LlmStreamFinish> finish,
                                               long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (finish.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        LlmStreamFinish result = finish.get();
        if (result == null) {
            throw new IllegalStateException(label + " finish is null");
        }
        return result;
    }

    private static void printUsage(String label, LlmTokenUsage usage) {
        System.out.printf("%s.usage prompt=%d completion=%d total=%d%n",
                label, usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
    }

    private static void writeResult(PrintWriter output, String label, LlmGenerationResult result) {
        if (output == null) return;
        writeOutput(output, label + ".text", result.text());
        writeOutput(output, label + ".usage", usageLine(result.usage()));
    }

    private static void writeStreamResult(PrintWriter output, String label, String text, LlmStreamFinish finish) {
        if (output == null) return;
        writeOutput(output, label + ".text", text);
        writeOutput(output, label + ".finish", finish.type().name());
        writeOutput(output, label + ".usage", usageLine(finish.usage()));
    }

    private static void writeOutput(PrintWriter output, String key, String value) {
        if (output == null) return;
        output.println("## " + key);
        output.println(value == null ? "<null>" : value);
        output.println();
    }

    private static String usageLine(LlmTokenUsage usage) {
        return "prompt=" + usage.promptTokens()
                + ", completion=" + usage.completionTokens()
                + ", total=" + usage.totalTokens();
    }

    private static String compact(String text) {
        if (text == null) return "<null>";
        String compact = text.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }
}
