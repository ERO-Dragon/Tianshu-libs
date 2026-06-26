package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.SpeculativeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Field;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "tianshu.llm.model", matches = ".+")
class LlamaEngineInterruptIntegrationTest {
    private JavaLlamaServer service;
    private static final int LONG_MTP_CONTEXT_TOKENS = 8192;
    private static final int LONG_MTP_TARGET_PROMPT_TOKENS = 7000;
    private static final int LONG_MTP_MAX_TOKENS = 1024;
    private static final int LONG_MTP_DRAFT_MAX = 1;
    private static final int LONG_MTP_CONTEXT_RESERVE = 64;
    private static final String LONG_MTP_CONTEXT_BLOCK = """
            Operations note: The player is maintaining a busy overworld base with villager trading,
            storage sorting, crop farms, a kelp-powered smelter, a bamboo reserve, a minecart route,
            and a compact Nether hub. The plan must avoid lava near wooden stairs, keep villager paths
            two blocks wide, protect water streams from mob pathfinding, label overflow storage, reduce
            lag during combat, preserve reversible redstone changes, and mark cave exits clearly.
            Expedition note: Before cave work, the player should bring food, shields, torches, blocks,
            water, spare tools, ladders, and shulker boxes. They should avoid digging straight down,
            avoid fighting near lava ledges, retreat when durability is low, and place route markers
            every few turns so casual players can find their way back without using unmarked portals.
            """;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    @Test
    void chatAndTaskStreamWorkWithRealModel() throws Exception {
        service = newService(1);
        service.start();
        assertTrue(service.isReady());

        SamplerConfig sampler = deterministicSampler(false);
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly this word: READY")),
                sampler,
                24
        ));
        assertUsableOutput(chat);

        StringBuilder streamed = new StringBuilder();
        CompletableFuture<String> task = service.taskStream(
                taskPrompt("Write a numbered list from 1 to 12, one short item per line."),
                sampler,
                96,
                0,
                false,
                streamed::append
        );

        String result = task.get(180, TimeUnit.SECONDS);
        assertUsableOutput(result);
        assertEquals(result, streamed.toString());
    }

    @Test
    void taskStreamSurvivesSingleChatInterrupt() throws Exception {
        service = newService(1);
        service.start();

        StreamCapture capture = new StreamCapture(20);
        CompletableFuture<String> task = service.taskStream(
                taskPrompt("Count from 1 to 80 with commas. Continue until done."),
                deterministicSampler(false),
                180,
                0,
                false,
                capture::accept
        );

        assertTrue(capture.await(90), "task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: INTERRUPT_OK")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        String result = task.get(240, TimeUnit.SECONDS);
        assertUsableOutput(result);
        assertEquals(result, capture.text());
        assertNoUnnormalizedReasoningTags(result);
    }

    @Test
    void mtpTaskStreamSurvivesSingleChatInterrupt() throws Exception {
        service = newService(1, 8192);
        service.start();
        assumeTrue(service.supportsMtp(), "model does not support MTP");

        StreamCapture capture = new StreamCapture(20);
        CompletableFuture<String> task = service.taskStream(
                taskPrompt("Write 220 comma-separated integers, starting from 1. Continue until the list is complete."),
                deterministicSampler(false),
                420,
                0,
                false,
                mtpOptions(),
                capture::accept
        );

        assertTrue(capture.await(120), "MTP task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: MTP_INTERRUPT_OK")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        String result = task.get(360, TimeUnit.SECONDS);
        assertUsableOutput(result);
        assertEquals(result, capture.text());
        assertNoUnnormalizedReasoningTags(result);
    }

    @Test
    void taskStreamSurvivesMultipleChatInterrupts() throws Exception {
        service = newService(1);
        service.start();

        StreamCapture capture = new StreamCapture(15);
        CompletableFuture<String> task = service.taskStream(
                taskPrompt("Write 120 comma-separated integers, starting from 1."),
                deterministicSampler(false),
                220,
                0,
                false,
                capture::accept
        );

        for (int i = 0; i < 3; i++) {
            assertTrue(capture.awaitMore(15, 120), "task stream did not progress before interrupt " + i);
            String chat = awaitChat(service.chat(
                    List.of(ChatMessage.user("Reply with exactly: CHAT_" + i)),
                    deterministicSampler(false),
                    24
            ));
            assertUsableOutput(chat);
        }

        String result = task.get(300, TimeUnit.SECONDS);
        assertUsableOutput(result);
        assertEquals(result, capture.text());
        assertNoUnnormalizedReasoningTags(result);
    }

    @Test
    void queuedTaskStreamsRemainSeparated() throws Exception {
        service = newService(2);
        service.start();

        StreamCapture firstCapture = new StreamCapture(10);
        StreamCapture secondCapture = new StreamCapture(1);
        CompletableFuture<String> first = service.taskStream(
                taskPrompt("Write token FIRST ten times separated by spaces."),
                deterministicSampler(false),
                80,
                0,
                false,
                firstCapture::accept
        );
        CompletableFuture<String> second = service.taskStream(
                taskPrompt("Write token SECOND ten times separated by spaces."),
                deterministicSampler(false),
                80,
                0,
                false,
                secondCapture::accept
        );

        String firstResult = first.get(240, TimeUnit.SECONDS);
        String secondResult = second.get(240, TimeUnit.SECONDS);

        assertUsableOutput(firstResult);
        assertUsableOutput(secondResult);
        assertEquals(firstResult, firstCapture.text());
        assertEquals(secondResult, secondCapture.text());
        assertFalse(firstResult.equals(secondResult), "queued tasks returned identical text unexpectedly");
    }

    @Test
    void suspendedTaskCanBeCancelledAndSlotIsReleased() throws Exception {
        service = newService(1);
        service.start();

        StreamCapture capture = new StreamCapture(10);
        CompletableFuture<String> task = service.taskStream(
                taskPrompt("Write 100 comma-separated integers, starting from 1."),
                deterministicSampler(false),
                200,
                0,
                false,
                capture::accept
        );

        assertTrue(capture.await(90), "task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: CANCEL_INTERRUPT")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        assertTrue(task.cancel(false));
        Thread.sleep(200);

        StreamCapture nextCapture = new StreamCapture(1);
        CompletableFuture<String> next = service.taskStream(
                taskPrompt("Reply with exactly: AFTER_CANCEL"),
                deterministicSampler(false),
                32,
                0,
                false,
                nextCapture::accept
        );
        String nextResult = next.get(180, TimeUnit.SECONDS);
        assertUsableOutput(nextResult);
        assertEquals(nextResult, nextCapture.text());
    }

    @Test
    void preemptedTaskCanColdResumeWhenHotSuspendLimitIsOne() throws Exception {
        service = newService(1);
        service.start();

        StreamCapture lowCapture = new StreamCapture(10);
        CompletableFuture<String> low = service.taskStream(
                taskPrompt("Write 80 comma-separated integers, starting from 1."),
                deterministicSampler(false),
                160,
                0,
                true,
                lowCapture::accept
        );

        assertTrue(lowCapture.await(90), "low priority task stream did not start");

        StreamCapture highCapture = new StreamCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: HIGH_PRIORITY"),
                deterministicSampler(false),
                32,
                10,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(90), "high priority task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: CHAT_DURING_PREEMPTION")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        String highResult = high.get(180, TimeUnit.SECONDS);
        String lowResult = low.get(240, TimeUnit.SECONDS);

        assertUsableOutput(highResult);
        assertUsableOutput(lowResult);
        assertEquals(highResult, highCapture.text());
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);
    }

    @Test
    void mtpPreemptedTaskCanColdResumeAfterHigherPriorityTask() throws Exception {
        service = newService(1, 8192);
        service.start();
        assumeTrue(service.supportsMtp(), "model does not support MTP");

        StreamCapture lowCapture = new StreamCapture(10);
        CompletableFuture<String> low = service.taskStream(
                taskPrompt("Write 240 comma-separated integers, starting from 1. Continue until the list is complete."),
                deterministicSampler(false),
                520,
                0,
                true,
                mtpOptions(),
                lowCapture::accept
        );

        assertTrue(lowCapture.await(120), "low priority MTP task stream did not start");

        StreamCapture highCapture = new StreamCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: HIGH_PRIORITY_OVER_MTP"),
                deterministicSampler(false),
                32,
                10,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(120), "high priority task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: CHAT_DURING_MTP_PREEMPTION")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        String highResult = high.get(240, TimeUnit.SECONDS);
        String lowResult = low.get(420, TimeUnit.SECONDS);

        assertUsableOutput(highResult);
        assertUsableOutput(lowResult);
        assertEquals(highResult, highCapture.text());
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);
    }

    @Test
    @EnabledIfSystemProperty(named = "tianshu.llm.perf", matches = "true")
    void mtpColdResumeWithLongPrompt8k() throws Exception {
        service = newService(1, LONG_MTP_CONTEXT_TOKENS);
        service.start();
        assumeTrue(service.supportsMtp(), "model does not support MTP");

        PerfCapture lowCapture = new PerfCapture(1);
        LlamaEngine engine = loadedEngine(service);
        LongMtpPrompt prompt = longMtpPrompt(engine);
        long submittedNanos = System.nanoTime();
        CompletableFuture<String> low = service.taskStream(
                prompt.messages(),
                deterministicSampler(false),
                LONG_MTP_MAX_TOKENS,
                0,
                true,
                mtpOptions(),
                lowCapture::accept
        );

        assertTrue(lowCapture.awaitFutureProgress(low, 300), "long-prompt MTP task did not emit a token before interruption");

        PerfCapture highCapture = new PerfCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: LONG_MTP_HIGH"),
                deterministicSampler(false),
                32,
                10,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(180), "high priority task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: LONG_MTP_CHAT")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        int countAtColdResume = lowCapture.tokenCount();
        String highResult = high.get(240, TimeUnit.SECONDS);
        long highFinishedNanos = System.nanoTime();
        assertUsableOutput(highResult);

        assertTrue(lowCapture.awaitTokenCount(countAtColdResume + 1, 360),
                "long-prompt MTP task did not emit a token after cold resume");
        long firstPostColdTokenNanos = lowCapture.firstTokenAtOrAfter(countAtColdResume + 1);

        String lowResult = low.get(540, TimeUnit.SECONDS);
        long finishedNanos = System.nanoTime();
        assertUsableOutput(lowResult);
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);

        double ttftMs = (lowCapture.firstTokenAtOrAfter(1) - submittedNanos) / 1_000_000.0;
        double coldResumeToFirstTokenMs = (firstPostColdTokenNanos - highFinishedNanos) / 1_000_000.0;
        double totalSeconds = Math.max(0.001, (finishedNanos - submittedNanos) / 1_000_000_000.0);

        System.out.println("=== LONG MTP COLD RESUME 8K ===");
        System.out.println("context_tokens=" + LONG_MTP_CONTEXT_TOKENS);
        System.out.println("target_prompt_tokens=" + LONG_MTP_TARGET_PROMPT_TOKENS);
        System.out.println("actual_prompt_tokens=" + prompt.tokenCount());
        System.out.println("max_tokens=" + LONG_MTP_MAX_TOKENS);
        System.out.println("draft_max=" + LONG_MTP_DRAFT_MAX);
        System.out.println("prompt_chars=" + prompt.userContent().length());
        System.out.println("pre_cold_callback_chunks=" + countAtColdResume);
        System.out.println("total_callback_chunks=" + lowCapture.tokenCount());
        System.out.printf("long_mtp_ttft_ms=%.2f%n", ttftMs);
        System.out.printf("long_mtp_cold_resume_ttft_ms=%.2f%n", coldResumeToFirstTokenMs);
        System.out.printf("long_mtp_total_seconds=%.2f%n", totalSeconds);
        System.out.println("=== END LONG MTP COLD RESUME 8K ===");
    }

    @Test
    void thinkingTaskCanColdResumeInsideThinkBlock() throws Exception {
        service = newService(1);
        service.start();

        StreamCapture lowCapture = new StreamCapture(1);
        CompletableFuture<String> low = service.taskStream(
                taskPrompt("Think step by step about 23 + 19, then answer with the final number."),
                deterministicSampler(true),
                220,
                0,
                true,
                lowCapture::accept
        );

        assertTrue(lowCapture.awaitUntil(text -> text.contains("<think>") && !text.contains("</think>"), 120),
                "low priority task did not enter a visible think block before interruption");

        StreamCapture highCapture = new StreamCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: HIGH_DURING_THINK"),
                deterministicSampler(false),
                32,
                10,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(90), "high priority task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: CHAT_DURING_THINK")),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);

        String highResult = high.get(180, TimeUnit.SECONDS);
        String lowResult = low.get(240, TimeUnit.SECONDS);

        assertUsableOutput(highResult);
        assertUsableOutput(lowResult);
        assertEquals(highResult, highCapture.text());
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);
        assertBalancedThinkTags(lowResult);
        assertTrue(lowResult.contains("<think>"), "cold-resumed thinking output should keep canonical think wrapper");
    }

    @Test
    @EnabledIfSystemProperty(named = "tianshu.llm.samples", matches = "true")
    void printColdResumeThinkingSamples() throws Exception {
        service = newService(1);
        service.start();

        List<String> prompts = List.of(
                "Use brief reasoning to solve 23 + 19, then answer with the final number.",
                "Use brief reasoning and suggest a safe Minecraft mining plan for a player with iron tools.",
                "Use brief reasoning to compare iron armor with gold armor in Minecraft, then give one recommendation."
        );

        for (int i = 0; i < prompts.size(); i++) {
            String result = runColdResumeThinkingSample(prompts.get(i), i);
            System.out.println("=== COLD RESUME SAMPLE " + (i + 1) + " ===");
            System.out.println(result);
            System.out.println("=== END SAMPLE " + (i + 1) + " ===");
            assertUsableOutput(result);
            assertNoUnnormalizedReasoningTags(result);
            assertBalancedThinkTags(result);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "tianshu.llm.perf", matches = "true")
    void measureNoThinkingChatLatencyWithLongPrompt8k() throws Exception {
        service = newService(1, 8192);
        service.start();
        warmUpLongPromptChat(false);

        PerfCapture capture = new PerfCapture(1);
        long submittedNanos = System.nanoTime();
        service.chatStream(
                taskPrompt(longRealtimeChatPrompt()),
                deterministicSampler(false),
                capture::accept
        ).get(300, TimeUnit.SECONDS);
        long finishedNanos = System.nanoTime();

        assertUsableOutput(capture.text());
        assertNoUnnormalizedReasoningTags(capture.text());
        long firstTokenNanos = capture.firstTokenAtOrAfter(1);
        double ttftMs = (firstTokenNanos - submittedNanos) / 1_000_000.0;
        double generationSeconds = Math.max(0.001, (finishedNanos - firstTokenNanos) / 1_000_000_000.0);
        double chunksPerSecond = capture.tokenCount() / generationSeconds;

        System.out.println("=== CHAT NO THINK PERF 8K WARMED ===");
        System.out.println("context_tokens=8192");
        System.out.println("prompt_chars=" + longRealtimeChatPrompt().length());
        System.out.println("callback_chunks=" + capture.tokenCount());
        System.out.printf("chat_no_think_ttft_ms=%.2f%n", ttftMs);
        System.out.printf("chunks_per_sec=%.2f%n", chunksPerSecond);
        System.out.println("=== END CHAT NO THINK PERF 8K WARMED ===");
    }

    @Test
    @EnabledIfSystemProperty(named = "tianshu.llm.perf", matches = "true")
    void measureNoInterruptLatencyWithLongPrompt8k() throws Exception {
        service = newService(1, 8192);
        service.start();
        warmUpLongPromptTask();

        PerfCapture capture = new PerfCapture(1);
        long submittedNanos = System.nanoTime();
        CompletableFuture<String> task = service.taskStream(
                taskPrompt(longPrompt()),
                deterministicSampler(true),
                360,
                0,
                true,
                capture::accept
        );

        assertTrue(capture.await(300), "long prompt task did not emit a stream callback");
        long firstTokenNanos = capture.firstTokenAtOrAfter(1);

        String result = task.get(420, TimeUnit.SECONDS);
        long finishedNanos = System.nanoTime();
        assertUsableOutput(result);
        assertEquals(result, capture.text());
        assertNoUnnormalizedReasoningTags(result);
        assertBalancedThinkTags(result);

        double ttftMs = (firstTokenNanos - submittedNanos) / 1_000_000.0;
        double generationSeconds = Math.max(0.001, (finishedNanos - firstTokenNanos) / 1_000_000_000.0);
        double chunksPerSecond = capture.tokenCount() / generationSeconds;

        System.out.println("=== NO INTERRUPT PERF 8K WARMED ===");
        System.out.println("context_tokens=8192");
        System.out.println("prompt_chars=" + longPrompt().length());
        System.out.println("callback_chunks=" + capture.tokenCount());
        System.out.printf("pure_ttft_ms=%.2f%n", ttftMs);
        System.out.printf("chunks_per_sec=%.2f%n", chunksPerSecond);
        System.out.println("=== END NO INTERRUPT PERF 8K WARMED ===");
    }

    @Test
    @EnabledIfSystemProperty(named = "tianshu.llm.perf", matches = "true")
    void measureColdResumeLatencyWithLongPrompt8k() throws Exception {
        service = newService(1, 8192);
        service.start();

        PerfCapture lowCapture = new PerfCapture(1);
        CompletableFuture<String> low = service.taskStream(
                taskPrompt(longPrompt()),
                deterministicSampler(true),
                360,
                0,
                true,
                lowCapture::accept
        );

        assertTrue(lowCapture.awaitUntil(text -> text.contains("<think>") && !text.contains("</think>"), 180),
                "long prompt task did not enter a visible think block before interruption");

        PerfCapture highCapture = new PerfCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: PERF_HIGH"),
                deterministicSampler(false),
                64,
                10,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(120), "high priority perf task did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: PERF_CHAT")),
                deterministicSampler(false),
                32
        ));
        assertUsableOutput(chat);

        int countAtColdResume = lowCapture.tokenCount();
        long coldResumeStartNanos = System.nanoTime();
        String highResult = high.get(240, TimeUnit.SECONDS);
        long highFinishedNanos = System.nanoTime();
        assertUsableOutput(highResult);

        assertTrue(lowCapture.awaitTokenCount(countAtColdResume + 1, 300),
                "cold-resumed task did not emit a token after high task completed");
        long firstPostColdTokenNanos = lowCapture.firstTokenAtOrAfter(countAtColdResume + 1);

        String lowResult = low.get(420, TimeUnit.SECONDS);
        long lowFinishedNanos = System.nanoTime();
        assertUsableOutput(lowResult);
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);
        assertBalancedThinkTags(lowResult);

        int postColdTokens = Math.max(0, lowCapture.tokenCount() - countAtColdResume);
        double coldResumeToFirstTokenMs = (firstPostColdTokenNanos - highFinishedNanos) / 1_000_000.0;
        double coldWindowTotalMs = (lowFinishedNanos - coldResumeStartNanos) / 1_000_000.0;
        double postColdGenerationSeconds = Math.max(0.001, (lowFinishedNanos - firstPostColdTokenNanos) / 1_000_000_000.0);
        double postColdTokensPerSecond = postColdTokens / postColdGenerationSeconds;

        System.out.println("=== COLD RESUME PERF 8K ===");
        System.out.println("context_tokens=8192");
        System.out.println("prompt_chars=" + longPrompt().length());
        System.out.println("pre_cold_callback_chunks=" + countAtColdResume);
        System.out.println("post_cold_callback_chunks=" + postColdTokens);
        System.out.printf("cold_resume_ttft_ms=%.2f%n", coldResumeToFirstTokenMs);
        System.out.printf("cold_window_total_ms=%.2f%n", coldWindowTotalMs);
        System.out.printf("post_cold_chunks_per_sec=%.2f%n", postColdTokensPerSecond);
        System.out.println("=== END COLD RESUME PERF 8K ===");
    }

    private JavaLlamaServer newService(int taskQueueSize) {
        return newService(taskQueueSize, 4096);
    }

    private JavaLlamaServer newService(int taskQueueSize, int contextSize) {
        // taskQueueSize is kept only to avoid rewriting older integration cases;
        // task suspend is now cold-only and does not retain hot KV slots.
        String model = System.getProperty("tianshu.llm.model");
        Path modelPath = Path.of(model);
        assertTrue(Files.isRegularFile(modelPath), "model not found: " + model);
        return JavaLlamaServer.builder()
                .model(model)
                .modelAlias("integration-qwen3-0.6b")
                .modelProfile("qwen3")
                .contextSize(contextSize)
                .chatThreads(2)
                .chatMaxQueueSize(4)
                .taskThreads(2)
                .taskSuspendOnChat(true)
                .gpuLayers(999)
                .requestTimeoutSeconds(300)
                .build();
    }

    private SamplerConfig deterministicSampler(boolean thinking) {
        SamplerConfig sampler = new SamplerConfig();
        sampler.setTemperature(0.0f);
        sampler.setEnableThinking(thinking);
        return sampler;
    }

    private InferenceOptions mtpOptions() {
        return InferenceOptions.builder()
                .mtpEnabled(true)
                .mtpDraftMax(LONG_MTP_DRAFT_MAX)
                .build();
    }

    private List<ChatMessage> taskPrompt(String userPrompt) {
        return List.of(
                ChatMessage.system("You are a concise test assistant. Follow the user instruction directly."),
                ChatMessage.user(userPrompt)
        );
    }

    private String longPrompt() {
        String paragraph = """
                Mining note: The player has iron tools, a shield, torches, food, and wants diamonds.
                Safety rule: avoid digging straight down, keep escape stairs, mark paths with torches,
                block lava with cobblestone, carry water, and return when inventory or food is low.
                Planning rule: give concise numbered steps and end with a short final recommendation.
                """;
        StringBuilder prompt = new StringBuilder();
        prompt.append("Use brief reasoning, then create a safe Minecraft mining plan from these notes.\n");
        for (int i = 0; i < 65; i++) {
            prompt.append("Section ").append(i + 1).append(": ").append(paragraph).append('\n');
        }
        prompt.append("Now provide the plan.");
        return prompt.toString();
    }

    private LongMtpPrompt longMtpPrompt(LlamaEngine engine) {
        int maxRunnablePromptTokens = LONG_MTP_CONTEXT_TOKENS
                - LONG_MTP_MAX_TOKENS
                - SpeculativeParams.requiredMtpTargetOutputs(LONG_MTP_DRAFT_MAX)
                - LONG_MTP_CONTEXT_RESERVE;
        assertTrue(maxRunnablePromptTokens >= MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS,
                "8k context is too small for this long-prompt MTP workload");
        int targetPromptTokens = Math.min(LONG_MTP_TARGET_PROMPT_TOKENS, maxRunnablePromptTokens);

        StringBuilder context = new StringBuilder();
        LongMtpPrompt prompt = formatLongMtpPrompt(engine, context.toString());
        assertTrue(prompt.tokenCount() <= maxRunnablePromptTokens,
                "base long MTP prompt exceeds runnable prompt budget");

        int block = 1;
        while (prompt.tokenCount() < targetPromptTokens) {
            int previousLength = context.length();
            context.append("Context block ").append(block++).append(":\n")
                    .append(LONG_MTP_CONTEXT_BLOCK)
                    .append('\n');
            LongMtpPrompt candidate = formatLongMtpPrompt(engine, context.toString());
            if (candidate.tokenCount() > maxRunnablePromptTokens) {
                context.setLength(previousLength);
                break;
            }
            prompt = candidate;
            if (block > 512) {
                throw new IllegalStateException("failed to build long MTP prompt");
            }
        }
        assertTrue(prompt.tokenCount() >= MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS,
                "long MTP prompt did not reach the heavy prompt threshold");
        return prompt;
    }

    private LongMtpPrompt formatLongMtpPrompt(LlamaEngine engine, String context) {
        String system = "You are a concise test assistant. Follow the user instruction directly.";
        String user = "Read all context notes, then write a practical Minecraft operations report. "
                + "Do not use hidden reasoning. Continue with concrete recommendations until the output budget is used.\n"
                + context
                + "Final instruction: produce a structured report with sections for safety, storage, villagers, routes, automation, and cave preparation.";
        List<ChatMessage> publicMessages = List.of(ChatMessage.system(system), ChatMessage.user(user));
        List<LlamaCppChatMessage> llamaMessages = List.of(
                new LlamaCppChatMessage("system", system),
                new LlamaCppChatMessage("user", user)
        );
        String formattedPrompt = engine.getModel().formatChatMessages(llamaMessages, deterministicSampler(false).effectiveThinkingMode());
        IntBuffer tokens = engine.getModel().getVocabulary().tokenize(formattedPrompt);
        return new LongMtpPrompt(publicMessages, user, tokens.remaining());
    }

    private String longRealtimeChatPrompt() {
        String paragraph = """
                Context note: The player has iron tools, a shield, torches, food, and wants diamonds.
                Safety rule: avoid digging straight down, keep escape stairs, mark paths with torches,
                block lava with cobblestone, carry water, and return when inventory or food is low.
                """;
        StringBuilder prompt = new StringBuilder();
        prompt.append("Read the context notes, then answer the final question directly.\n");
        for (int i = 0; i < 75; i++) {
            prompt.append("Context ").append(i + 1).append(": ").append(paragraph).append('\n');
        }
        prompt.append("Final question: What should the player do first? Answer in one concise sentence.");
        return prompt.toString();
    }

    private String runColdResumeThinkingSample(String prompt, int index) throws Exception {
        StreamCapture lowCapture = new StreamCapture(1);
        CompletableFuture<String> low = service.taskStream(
                taskPrompt(prompt),
                deterministicSampler(true),
                420,
                index,
                true,
                lowCapture::accept
        );

        assertTrue(lowCapture.awaitUntil(text -> text.contains("<think>") && !text.contains("</think>"), 120),
                "sample task did not enter a visible think block before interruption");

        StreamCapture highCapture = new StreamCapture(1);
        CompletableFuture<String> high = service.taskStream(
                taskPrompt("Reply with exactly: HIGH_SAMPLE_" + index),
                deterministicSampler(false),
                32,
                index + 100,
                false,
                highCapture::accept
        );

        assertTrue(highCapture.await(90), "sample high priority task stream did not start");
        String chat = awaitChat(service.chat(
                List.of(ChatMessage.user("Reply with exactly: CHAT_SAMPLE_" + index)),
                deterministicSampler(false),
                24
        ));
        assertUsableOutput(chat);
        assertUsableOutput(high.get(180, TimeUnit.SECONDS));

        String result = low.get(240, TimeUnit.SECONDS);
        assertEquals(result, lowCapture.text());
        return result;
    }

    private void warmUpLongPromptTask() throws Exception {
        CompletableFuture<String> warmup = service.taskStream(
                taskPrompt(longPrompt()),
                deterministicSampler(true),
                32,
                0,
                false,
                ignored -> {
                }
        );
        assertUsableOutput(warmup.get(300, TimeUnit.SECONDS));
    }

    private void warmUpLongPromptChat(boolean thinking) throws Exception {
        service.chatStream(
                taskPrompt(longRealtimeChatPrompt()),
                deterministicSampler(thinking),
                ignored -> {
                }
        ).get(300, TimeUnit.SECONDS);
    }

    private String awaitChat(CompletableFuture<String> chat) throws Exception {
        return chat.get(180, TimeUnit.SECONDS);
    }

    private void assertUsableOutput(String output) {
        assertNotNull(output);
        assertFalse(output.isBlank(), "model returned blank output");
    }

    private void assertNoUnnormalizedReasoningTags(String output) {
        assertFalse(output.contains("<reasoning>"));
        assertFalse(output.contains("</reasoning>"));
        assertFalse(output.contains("<thought>"));
        assertFalse(output.contains("</thought>"));
        assertFalse(output.contains("<analysis>"));
        assertFalse(output.contains("</analysis>"));
    }

    private void assertBalancedThinkTags(String output) {
        assertEquals(count(output, "<think>"), count(output, "</think>"), "think tags must be balanced");
    }

    private int count(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static class StreamCapture {
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch firstTokensLatch;
        private final AtomicInteger chars = new AtomicInteger();
        private final Object lock = new Object();
        private final List<String> callbackErrors = new ArrayList<>();

        private StreamCapture(int firstChars) {
            this.firstTokensLatch = new CountDownLatch(firstChars);
        }

        protected void accept(String token) {
            try {
                synchronized (lock) {
                    text.append(token);
                    chars.addAndGet(token.length());
                    afterAccept(token);
                    lock.notifyAll();
                }
                for (int i = 0; i < token.length(); i++) {
                    firstTokensLatch.countDown();
                }
            } catch (RuntimeException e) {
                callbackErrors.add(e.getMessage());
                throw e;
            }
        }

        protected void afterAccept(String token) {
        }

        protected Object lock() {
            return lock;
        }

        protected boolean await(long timeoutSeconds) throws InterruptedException {
            return firstTokensLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        protected boolean awaitFutureProgress(CompletableFuture<?> future, long timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
            while (true) {
                if (firstTokensLatch.await(1, TimeUnit.SECONDS)) return true;
                if (future.isDone()) {
                    future.get(1, TimeUnit.SECONDS);
                    return firstTokensLatch.getCount() == 0;
                }
                if (System.nanoTime() >= deadline) return false;
            }
        }

        protected boolean awaitMore(int additionalChars, long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
            synchronized (lock) {
                int target = chars.get() + additionalChars;
                while (chars.get() < target && callbackErrors.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
                return chars.get() >= target && callbackErrors.isEmpty();
            }
        }

        protected boolean awaitUntil(Predicate<String> predicate, long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
            synchronized (lock) {
                while (!predicate.test(text.toString()) && callbackErrors.isEmpty()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                }
                return predicate.test(text.toString()) && callbackErrors.isEmpty();
            }
        }

        protected String text() {
            synchronized (lock) {
                assertTrue(callbackErrors.isEmpty(), String.join(System.lineSeparator(), callbackErrors));
                return text.toString();
            }
        }
    }

    private static class PerfCapture extends StreamCapture {
        private final List<Long> tokenTimes = new ArrayList<>();

        private PerfCapture(int firstChars) {
            super(firstChars);
        }

        @Override
        protected void afterAccept(String token) {
            tokenTimes.add(System.nanoTime());
        }

        private int tokenCount() {
            synchronized (lock()) {
                return tokenTimes.size();
            }
        }

        private boolean awaitTokenCount(int target, long timeoutSeconds) throws InterruptedException {
            long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();
            synchronized (lock()) {
                while (tokenTimes.size() < target) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    TimeUnit.NANOSECONDS.timedWait(lock(), remaining);
                }
                return tokenTimes.size() >= target;
            }
        }

        private long firstTokenAtOrAfter(int oneBasedTokenCount) {
            synchronized (lock()) {
                return tokenTimes.get(oneBasedTokenCount - 1);
            }
        }
    }

    private LlamaEngine loadedEngine(JavaLlamaServer server) throws Exception {
        Field modelsField = JavaLlamaServer.class.getDeclaredField("models");
        modelsField.setAccessible(true);
        Object registry = modelsField.get(server);
        Field chatEngineField = registry.getClass().getDeclaredField("chatEngine");
        chatEngineField.setAccessible(true);
        return (LlamaEngine) chatEngineField.get(registry);
    }

    private record LongMtpPrompt(List<ChatMessage> messages, String userContent, int tokenCount) {
    }
}
