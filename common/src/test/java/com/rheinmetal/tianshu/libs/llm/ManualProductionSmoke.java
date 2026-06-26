package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ManualProductionSmoke {
    public static void main(String[] args) throws Exception {
        SmokeConfig config = SmokeConfig.fromProperties();
        config.validate();

        EventCapture events = new EventCapture();
        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(config.llmModel())
                .modelAlias(Path.of(config.llmModel()).getFileName().toString())
                .modelProfile(config.modelProfile())
                .contextSize(config.contextSize())
                .chatThreads(config.chatThreads())
                .chatMaxQueueSize(config.chatMaxQueueSize())
                .taskThreads(config.taskThreads())
                .taskSuspendOnChat(true)
                .gpuLayers(config.gpuLayers())
                .requestTimeoutSeconds(600)
                .inferenceEventListener(events::accept);

        if (!config.device().isBlank()) builder.device(config.device());
        if (!config.embeddingModel().isBlank()) {
            builder.embeddingModel(config.embeddingModel())
                    .embeddingContextSize(config.embeddingContextSize())
                    .embeddingThreads(config.embeddingThreads())
                    .embeddingGpuLayers(config.embeddingGpuLayers());
            if (!config.embeddingDevice().isBlank()) builder.embeddingDevice(config.embeddingDevice());
        }

        JavaLlamaServer service = builder.build();
        System.out.println("Manual production smoke");
        System.out.println("llmModel=" + config.llmModel());
        System.out.println("embeddingModel=" + (config.embeddingModel().isBlank() ? "<none>" : config.embeddingModel()));
        service.start();
        try {
            System.out.println("ready=" + service.isReady());
            System.out.println("supportsEnableThinking=" + service.supportsEnableThinking());
            System.out.println("supportsMtp=" + service.supportsMtp()
                    + " layers=" + service.getMtpCapability().getMtpLayerCount()
                    + " calibrated=" + service.getMtpCapability().isCalibrated());

            verifyInvalidSamplerRejected(service);
            verifyChatQueueBackpressure(service);
            verifyTaskColdResumeEvents(service, config);
            verifyRequestMtpInference(service, config);
            if (!config.embeddingModel().isBlank()) {
                verifyEmbeddingAndSearch(service);
            }

            events.printSummary();
        } finally {
            service.shutdown();
        }
    }

    private static void verifyInvalidSamplerRejected(JavaLlamaServer service) throws Exception {
        SamplerConfig invalid = deterministicSampler(false);
        invalid.setTemperature(Float.NaN);
        CompletableFuture<String> failed = service.chat(
                List.of(ChatMessage.user("This request should be rejected before native sampler creation.")),
                invalid,
                8
        );
        Throwable error = expectFailure(failed, "invalid sampler");
        if (!(rootCause(error) instanceof IllegalArgumentException)) {
            throw new IllegalStateException("invalid sampler should fail with IllegalArgumentException", error);
        }
        System.out.println("invalidSampler.rejected=" + rootCause(error).getMessage());
    }

    private static void verifyChatQueueBackpressure(JavaLlamaServer service) throws Exception {
        SamplerConfig sampler = deterministicSampler(false);
        CountDownLatch firstChatStarted = new CountDownLatch(1);
        CompletableFuture<String> active = service.chatStream(
                List.of(
                        ChatMessage.system("You are a concise assistant."),
                        ChatMessage.user("Count from 1 to 500 with commas. Continue until stopped.")
                ),
                sampler,
                512,
                null,
                token -> firstChatStarted.countDown()
        );
        if (!firstChatStarted.await(180, TimeUnit.SECONDS)) {
            throw new IllegalStateException("backpressure active chat did not start");
        }

        CompletableFuture<String> queued = service.chat(
                List.of(ChatMessage.user("Reply with exactly: QUEUED_CHAT")),
                sampler,
                16
        );
        CompletableFuture<String> rejected = service.chat(
                List.of(ChatMessage.user("Reply with exactly: SHOULD_REJECT")),
                sampler,
                16
        );
        Throwable error = expectFailure(rejected, "chat queue backpressure");
        if (!(rootCause(error) instanceof RejectedExecutionException)) {
            throw new IllegalStateException("chat queue should reject when full", error);
        }
        System.out.println("chatQueue.rejected=" + rootCause(error).getMessage());
        active.cancel(false);
        try {
            queued.get(180, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            queued.cancel(false);
        }
    }

    private static void verifyTaskColdResumeEvents(JavaLlamaServer service, SmokeConfig config) throws Exception {
        SamplerConfig sampler = deterministicSampler(false);
        CountDownLatch taskStarted = new CountDownLatch(1);
        StringBuilder taskText = new StringBuilder();
        AtomicReference<LlmStreamFinish> finish = new AtomicReference<>();
        CompletableFuture<LlmGenerationResult> task = service.taskStreamWithUsage(
                List.of(
                        ChatMessage.system("You are a concise Minecraft planning assistant."),
                        ChatMessage.user(longMinecraftPrompt(config.longPromptRepeats()))
                ),
                sampler,
                config.taskMaxTokens(),
                0,
                true,
                null,
                token -> {
                    taskText.append(token);
                    taskStarted.countDown();
                },
                finish::set
        );
        if (!taskStarted.await(180, TimeUnit.SECONDS)) {
            throw new IllegalStateException("cold resume task did not start");
        }
        LlmGenerationResult chat = service.chatWithUsage(
                List.of(ChatMessage.user("Reply with exactly: CHAT_INTERRUPT_OK")),
                sampler,
                24
        ).get(180, TimeUnit.SECONDS);
        requireUsable("interrupt chat", chat.text());

        LlmGenerationResult result = task.get(360, TimeUnit.SECONDS);
        requireUsable("cold resume task", result.text());
        if (!result.text().equals(taskText.toString())) {
            throw new IllegalStateException("task stream text does not match final result");
        }
        LlmStreamFinish completed = finish.get();
        if (completed == null || completed.type() != StreamFinishType.COMPLETED) {
            throw new IllegalStateException("task finish should be COMPLETED");
        }
        System.out.println("coldResume.usage prompt=" + result.usage().promptTokens()
                + " completion=" + result.usage().completionTokens());
    }

    private static void verifyRequestMtpInference(JavaLlamaServer service, SmokeConfig config) throws Exception {
        if (!service.supportsMtp()) {
            System.out.println("mtp.request.skipped=unsupported");
            return;
        }
        SamplerConfig sampler = deterministicSampler(false);
        InferenceOptions options = InferenceOptions.builder()
                .mtpEnabled(true)
                .mtpDraftMax(config.mtpDraftMax())
                .build();
        LlmGenerationResult result = service.chatWithUsage(
                List.of(
                        ChatMessage.system("You are a concise smoke-test assistant."),
                        ChatMessage.user("Reply with exactly: MTP_REQUEST_OK")
                ),
                sampler,
                32,
                options
        ).get(240, TimeUnit.SECONDS);
        requireUsable("mtp request", result.text());
        MtpCapability capability = service.getMtpCapability();
        System.out.println("mtp.request.text=" + compact(result.text()));
        System.out.println("mtp.capability calibrated=" + capability.isCalibrated()
                + " recommendedDraftMax=" + capability.getRecommendedDraftMax());
        if (capability.isCalibrated() && capability.getBestTrial() != null) {
            System.out.println("mtp.bestTrial draftMax=" + capability.getBestTrial().getDraftMax()
                    + " tps=" + capability.getBestTrial().getTokensPerSecond());
        }
    }

    private static void verifyEmbeddingAndSearch(JavaLlamaServer service) throws Exception {
        float[] query = service.embed("红石自动农场怎么规划");
        float[][] batch = service.embed(List.of("红石自动竹子农场", "村民交易大厅", "下界冰道交通"));
        if (query.length == 0 || batch.length != 3 || batch[0].length != query.length) {
            throw new IllegalStateException("embedding dimensions are inconsistent");
        }
        List<RagSearchResult> results = service.search(
                "红石自动农场",
                List.of("村民交易大厅规划", "红石自动竹子农场", "下界冰道交通枢纽"),
                2,
                0.0f
        );
        if (results.isEmpty()) {
            throw new IllegalStateException("search returned no results");
        }
        System.out.println("embedding.dim=" + query.length + " batch=" + batch.length);
        for (RagSearchResult result : results) {
            System.out.println("search.result score=" + result.getScore() + " content=" + result.getContent());
        }

        expectThrow(() -> service.embed(""), IllegalArgumentException.class, "blank embedding text");
        expectThrow(() -> service.embed(List.of("ok", "")), IllegalArgumentException.class, "blank embedding batch item");
        expectThrow(() -> service.search("query", List.of("doc"), 0, 0.0f), IllegalArgumentException.class, "invalid topK");
        expectThrow(() -> service.search("query", List.of("doc"), 1, Float.NaN), IllegalArgumentException.class, "invalid threshold");
    }

    private static SamplerConfig deterministicSampler(boolean thinking) {
        SamplerConfig sampler = SamplerConfig.defaults();
        sampler.setTemperature(0.0f);
        sampler.setEnableThinking(thinking);
        return sampler;
    }

    private static String longMinecraftPrompt(int repeats) {
        String block = "Base note: keep villager paths two blocks wide, label overflow chests, "
                + "avoid lava near wood, preserve item filters, mark cave exits, and reduce lag during combat. ";
        StringBuilder prompt = new StringBuilder("Create a practical operations checklist.\n");
        for (int i = 0; i < repeats; i++) {
            prompt.append("Section ").append(i + 1).append(": ").append(block).append('\n');
        }
        prompt.append("Final instruction: produce concrete numbered steps until the budget is used.");
        return prompt.toString();
    }

    private static Throwable expectFailure(CompletableFuture<?> future, String label) throws Exception {
        try {
            future.get(30, TimeUnit.SECONDS);
            throw new IllegalStateException(label + " unexpectedly succeeded");
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause();
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void expectThrow(ThrowingRunnable runnable, Class<? extends Throwable> type, String label) throws Exception {
        try {
            runnable.run();
            throw new IllegalStateException(label + " unexpectedly succeeded");
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                throw new IllegalStateException(label + " failed with unexpected type: " + t.getClass(), t);
            }
            System.out.println(label + ".rejected=" + t.getMessage());
        }
    }

    private static void requireUsable(String label, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(label + " returned blank text");
        }
    }

    private static String compact(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class EventCapture {
        private final List<InferenceEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());

        private void accept(InferenceEvent event) {
            events.add(event);
            System.out.println("event type=" + event.getType()
                    + " lane=" + event.getLane()
                    + " task=" + event.getTaskId()
                    + " replayChars=" + event.getReplayCharacters()
                    + " generated=" + event.getGeneratedTokens());
        }

        private void printSummary() {
            Map<InferenceEventType, AtomicInteger> counts = new EnumMap<>(InferenceEventType.class);
            synchronized (events) {
                for (InferenceEvent event : events) {
                    counts.computeIfAbsent(event.getType(), ignored -> new AtomicInteger()).incrementAndGet();
                }
            }
            System.out.println("event.summary=" + counts);
            requireEvent(InferenceEventType.QUEUED);
            requireEvent(InferenceEventType.STARTED);
            requireEvent(InferenceEventType.GENERATION_STARTED);
            requireEvent(InferenceEventType.COMPLETED);
            requireEvent(InferenceEventType.SUSPENDED);
            requireEvent(InferenceEventType.COLD_RESUME_STARTED);
            requireEvent(InferenceEventType.PREFILL_COMPLETED);
            requireEvent(InferenceEventType.COLD_RESUME_COMPLETED);
        }

        private void requireEvent(InferenceEventType type) {
            synchronized (events) {
                for (InferenceEvent event : events) {
                    if (event.getType() == type) return;
                }
            }
            throw new IllegalStateException("missing expected inference event: " + type);
        }
    }

    private record SmokeConfig(String llmModel,
                               String embeddingModel,
                               String modelProfile,
                               String device,
                               String embeddingDevice,
                               int contextSize,
                               int embeddingContextSize,
                               int chatThreads,
                               int taskThreads,
                               int embeddingThreads,
                               int chatMaxQueueSize,
                               int gpuLayers,
                               int embeddingGpuLayers,
                               int taskMaxTokens,
                               int longPromptRepeats,
                               int mtpDraftMax) {
        private static SmokeConfig fromProperties() {
            return new SmokeConfig(
                    System.getProperty("tianshu.llm.model", ""),
                    System.getProperty("tianshu.embedding.model", ""),
                    System.getProperty("tianshu.llm.modelProfile", "auto"),
                    System.getProperty("tianshu.llm.device", ""),
                    System.getProperty("tianshu.embedding.device", ""),
                    Integer.getInteger("tianshu.llm.context", 8192),
                    Integer.getInteger("tianshu.embedding.context", 8192),
                    Integer.getInteger("tianshu.llm.chatThreads", 4),
                    Integer.getInteger("tianshu.llm.taskThreads", 2),
                    Integer.getInteger("tianshu.embedding.threads", 4),
                    Integer.getInteger("tianshu.llm.chatMaxQueueSize", 1),
                    Integer.getInteger("tianshu.llm.gpuLayers", 999),
                    Integer.getInteger("tianshu.embedding.gpuLayers", 999),
                    Integer.getInteger("tianshu.llm.taskMaxTokens", 240),
                    Integer.getInteger("tianshu.llm.longPromptRepeats", 80),
                    Integer.getInteger("tianshu.mtp.draftMax", 1)
            );
        }

        private void validate() {
            if (llmModel.isBlank()) throw new IllegalArgumentException("tianshu.llm.model is required");
            if (!Files.isRegularFile(Path.of(llmModel))) throw new IllegalArgumentException("LLM model not found: " + llmModel);
            if (!embeddingModel.isBlank() && !Files.isRegularFile(Path.of(embeddingModel))) {
                throw new IllegalArgumentException("Embedding model not found: " + embeddingModel);
            }
        }
    }
}
