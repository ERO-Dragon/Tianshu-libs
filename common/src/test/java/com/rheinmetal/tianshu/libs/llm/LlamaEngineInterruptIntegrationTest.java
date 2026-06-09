package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "tianshu.llm.model", matches = ".+")
class LlamaEngineInterruptIntegrationTest {
    private JavaLlamaServer service;

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
        String chat = service.chat(
                List.of(ChatMessage.user("Reply with exactly this word: READY")),
                sampler,
                24
        );
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
        String chat = service.chat(
                List.of(ChatMessage.user("Reply with exactly: INTERRUPT_OK")),
                deterministicSampler(false),
                24
        );
        assertUsableOutput(chat);

        String result = task.get(240, TimeUnit.SECONDS);
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
            String chat = service.chat(
                    List.of(ChatMessage.user("Reply with exactly: CHAT_" + i)),
                    deterministicSampler(false),
                    24
            );
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
        String chat = service.chat(
                List.of(ChatMessage.user("Reply with exactly: CANCEL_INTERRUPT")),
                deterministicSampler(false),
                24
        );
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
        String chat = service.chat(
                List.of(ChatMessage.user("Reply with exactly: CHAT_DURING_PREEMPTION")),
                deterministicSampler(false),
                24
        );
        assertUsableOutput(chat);

        String highResult = high.get(180, TimeUnit.SECONDS);
        String lowResult = low.get(240, TimeUnit.SECONDS);

        assertUsableOutput(highResult);
        assertUsableOutput(lowResult);
        assertEquals(highResult, highCapture.text());
        assertEquals(lowResult, lowCapture.text());
        assertNoUnnormalizedReasoningTags(lowResult);
    }

    private JavaLlamaServer newService(int taskQueueSize) {
        String model = System.getProperty("tianshu.llm.model");
        Path modelPath = Path.of(model);
        assertTrue(Files.isRegularFile(modelPath), "model not found: " + model);
        return JavaLlamaServer.builder()
                .model(model)
                .modelAlias("integration-qwen3-0.6b")
                .modelProfile("qwen3")
                .chatContext(4096)
                .chatThreads(2)
                .chatMaxQueueSize(4)
                .taskContext(4096)
                .taskThreads(2)
                .taskMaxQueueSize(taskQueueSize)
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

    private List<ChatMessage> taskPrompt(String userPrompt) {
        return List.of(
                ChatMessage.system("You are a concise test assistant. Follow the user instruction directly."),
                ChatMessage.user(userPrompt)
        );
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

    private static class StreamCapture {
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch firstTokensLatch;
        private final AtomicInteger chars = new AtomicInteger();
        private final Object lock = new Object();
        private final List<String> callbackErrors = new ArrayList<>();

        private StreamCapture(int firstChars) {
            this.firstTokensLatch = new CountDownLatch(firstChars);
        }

        private void accept(String token) {
            try {
                synchronized (lock) {
                    text.append(token);
                    chars.addAndGet(token.length());
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

        private boolean await(long timeoutSeconds) throws InterruptedException {
            return firstTokensLatch.await(timeoutSeconds, TimeUnit.SECONDS);
        }

        private boolean awaitMore(int additionalChars, long timeoutSeconds) throws InterruptedException {
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

        private String text() {
            synchronized (lock) {
                assertTrue(callbackErrors.isEmpty(), String.join(System.lineSeparator(), callbackErrors));
                return text.toString();
            }
        }
    }
}
