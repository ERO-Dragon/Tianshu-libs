package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ManualLatencyBenchmark {
    private static final String DEFAULT_MODEL =
            "D:\\Minecraft\\Qwen3.5-9B-DeepSeek-V4-Flash-Q4_K_M.gguf";

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("tianshu.llm.model", DEFAULT_MODEL);
        int context = Integer.getInteger("tianshu.llm.context", 8192);
        int gpuLayers = Integer.getInteger("tianshu.llm.gpuLayers", 999);
        int maxTokens = Integer.getInteger("tianshu.llm.maxTokens", 128);
        int promptChars = Integer.getInteger("tianshu.llm.promptChars", 24000);
        int warmup = Integer.getInteger("tianshu.llm.warmup", 1);
        int runs = Integer.getInteger("tianshu.llm.runs", 3);
        int chatThreads = Integer.getInteger("tianshu.llm.chatThreads", 4);
        int gpuIndex = Integer.getInteger("tianshu.llm.gpuIndex", 0);
        String profile = System.getProperty("tianshu.llm.modelProfile", "auto");
        String device = System.getProperty("tianshu.llm.device", "");

        SamplerConfig sampler = new SamplerConfig();
        sampler.setTemperature(Float.parseFloat(System.getProperty("tianshu.llm.temperature", "0.0")));
        sampler.setEnableThinking(Boolean.parseBoolean(System.getProperty("tianshu.llm.enableThinking", "false")));

        List<ChatMessage> messages = benchmarkMessages(promptChars);
        System.out.printf(
                "Manual latency benchmark%nmodel=%s%ncontext=%d, gpuLayers=%d, chatThreads=%d, maxTokens=%d, promptChars=%d, warmup=%d, runs=%d%n",
                model, context, gpuLayers, chatThreads, maxTokens, promptChars, warmup, runs
        );

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(model)
                .modelAlias("manual-latency-benchmark")
                .modelProfile(profile)
                .contextSize(context)
                .chatThreads(chatThreads)
                .chatMaxQueueSize(1)
                .taskThreads(1)
                .taskSuspendOnChat(true)
                .gpuLayers(gpuLayers)
                .requestTimeoutSeconds(600)
                .inferenceEventListener(event -> currentRun.get().onEvent(event, gpuIndex));
        if (!device.isBlank()) {
            builder.device(device);
        }

        MemorySample beforeStart = sampleGpuMemory(gpuIndex);
        System.out.println("gpu memory before start: " + beforeStart);
        JavaLlamaServer service = builder.build();
        service.start();
        MemorySample afterStart = sampleGpuMemory(gpuIndex);
        System.out.println("gpu memory after model load: " + afterStart
                + ", model/load delta=" + afterStart.deltaUsedMb(beforeStart) + " MiB");
        try {
            for (int i = 1; i <= warmup; i++) {
                Result result = runOnce(service, messages, sampler, maxTokens);
                printResult("warmup-" + i, result);
            }

            List<Result> measured = new ArrayList<>();
            for (int i = 1; i <= runs; i++) {
                Result result = runOnce(service, messages, sampler, maxTokens);
                measured.add(result);
                printResult("run-" + i, result);
            }
            printSummary(measured);
        } finally {
            service.shutdown();
            MemorySample afterShutdown = sampleGpuMemory(gpuIndex);
            System.out.println("gpu memory after shutdown: " + afterShutdown);
        }
    }

    private static final AtomicReference<RunProbe> currentRun = new AtomicReference<>(RunProbe.noop());

    private static Result runOnce(JavaLlamaServer service,
                                  List<ChatMessage> messages,
                                  SamplerConfig sampler,
                                  int maxTokens) throws Exception {
        AtomicInteger chunks = new AtomicInteger();
        AtomicInteger chars = new AtomicInteger();
        AtomicLong prefillStart = new AtomicLong();
        AtomicLong prefillDone = new AtomicLong();
        AtomicLong firstChunk = new AtomicLong();
        RunProbe probe = new RunProbe(prefillStart, prefillDone);

        long submitted = System.nanoTime();
        probe.submitted.set(submitted);
        currentRun.set(probe);
        StringBuilder response = new StringBuilder();
        try {
            service.chatStream(messages, sampler, maxTokens, chunk -> {
                long now = System.nanoTime();
                if (firstChunk.compareAndSet(0L, now)) {
                    probe.firstTokenMemory.compareAndSet(null, sampleGpuMemory(probe.gpuIndex));
                }
                chunks.incrementAndGet();
                chars.addAndGet(chunk.length());
                response.append(chunk);
            }).get();
        } finally {
            currentRun.compareAndSet(probe, RunProbe.noop());
        }
        long completed = System.nanoTime();
        MemorySample afterClose = sampleGpuMemory(probe.gpuIndex);

        return new Result(
                submitted,
                prefillStart.get(),
                prefillDone.get(),
                firstChunk.get(),
                completed,
                chunks.get(),
                chars.get(),
                response.length(),
                probe.prefillStartedMemory.get(),
                probe.prefillCompletedMemory.get(),
                probe.generationStartedMemory.get(),
                probe.firstTokenMemory.get(),
                afterClose
        );
    }

    private static List<ChatMessage> benchmarkMessages(int promptChars) {
        StringBuilder context = new StringBuilder(promptChars + 512);
        int paragraph = 1;
        while (context.length() < promptChars) {
            context.append("Fact block ").append(paragraph++).append(": ")
                    .append("In this Minecraft settlement, villagers trade emeralds, farmers manage wheat, ")
                    .append("miners report cave depth, guards watch hostile mobs, and the assistant must answer ")
                    .append("using only the supplied settlement notes. Keep responses direct and avoid long reasoning. ");
        }
        if (context.length() > promptChars) {
            context.setLength(promptChars);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(
                "You are a concise Minecraft assistant. Use the supplied context. Do not reason step by step."
        ));
        messages.add(ChatMessage.user(
                "Context:\n" + context + "\n\nQuestion: Summarize the settlement status in three short bullet points."
        ));
        return messages;
    }

    private static void printResult(String label, Result r) {
        System.out.printf(
                "%s: ttft=%.2f ms, prefill=%.2f ms, first-after-prefill=%.2f ms, total=%.2f ms, chunks=%d, chars=%d, chunks/sec=%.2f%n",
                label,
                r.ttftMs(),
                r.prefillMs(),
                r.firstAfterPrefillMs(),
                r.totalMs(),
                r.chunks,
                r.chars,
                r.chunksPerSecond()
        );
        System.out.printf(
                "%s memory: prefill-start=%s, prefill-done=%s, generation-start=%s, first-token=%s, after-close=%s, active-context-delta=%s MiB%n",
                label,
                r.prefillStartedMemory,
                r.prefillCompletedMemory,
                r.generationStartedMemory,
                r.firstTokenMemory,
                r.afterCloseMemory,
                r.activeContextDeltaMb()
        );
    }

    private static void printSummary(List<Result> results) {
        if (results.isEmpty()) return;
        System.out.printf(
                "summary: avg ttft=%.2f ms, avg prefill=%.2f ms, avg total=%.2f ms, avg chunks/sec=%.2f%n",
                average(results, Result::ttftMs),
                average(results, Result::prefillMs),
                average(results, Result::totalMs),
                average(results, Result::chunksPerSecond)
        );
    }

    private static double average(List<Result> results, Metric metric) {
        double sum = 0.0;
        for (Result result : results) {
            sum += metric.value(result);
        }
        return sum / results.size();
    }

    private record Result(long submitted,
                          long prefillStart,
                          long prefillDone,
                          long firstChunk,
                          long completed,
                          int chunks,
                          int chars,
                          int responseChars,
                          MemorySample prefillStartedMemory,
                          MemorySample prefillCompletedMemory,
                          MemorySample generationStartedMemory,
                          MemorySample firstTokenMemory,
                          MemorySample afterCloseMemory) {
        double ttftMs() {
            long first = firstChunk == 0L ? completed : firstChunk;
            return millis(first - submitted);
        }

        double prefillMs() {
            if (prefillStart == 0L || prefillDone == 0L) return 0.0;
            return millis(prefillDone - prefillStart);
        }

        double firstAfterPrefillMs() {
            if (prefillDone == 0L || firstChunk == 0L) return 0.0;
            return millis(firstChunk - prefillDone);
        }

        double totalMs() {
            return millis(completed - submitted);
        }

        double chunksPerSecond() {
            long start = firstChunk == 0L ? submitted : firstChunk;
            double seconds = Math.max(0.001, (completed - start) / 1_000_000_000.0);
            return chunks / seconds;
        }

        String activeContextDeltaMb() {
            MemorySample peak = MemorySample.maxUsed(prefillCompletedMemory, generationStartedMemory, firstTokenMemory);
            if (peak == null || afterCloseMemory == null) return "n/a";
            return Long.toString(peak.usedMb - afterCloseMemory.usedMb);
        }

        private static double millis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }

    private interface Metric {
        double value(Result result);
    }

    private static class RunProbe {
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong prefillStart;
        private final AtomicLong prefillDone;
        private final AtomicReference<MemorySample> prefillStartedMemory = new AtomicReference<>();
        private final AtomicReference<MemorySample> prefillCompletedMemory = new AtomicReference<>();
        private final AtomicReference<MemorySample> generationStartedMemory = new AtomicReference<>();
        private final AtomicReference<MemorySample> firstTokenMemory = new AtomicReference<>();
        private volatile int gpuIndex;

        private RunProbe(AtomicLong prefillStart, AtomicLong prefillDone) {
            this.prefillStart = prefillStart;
            this.prefillDone = prefillDone;
        }

        private static RunProbe noop() {
            return new RunProbe(new AtomicLong(), new AtomicLong());
        }

        private void onEvent(InferenceEvent event, int gpuIndex) {
            this.gpuIndex = gpuIndex;
            switch (event.getType()) {
                case PREFILL_STARTED -> {
                    prefillStart.compareAndSet(0L, System.nanoTime());
                    prefillStartedMemory.compareAndSet(null, sampleGpuMemory(gpuIndex));
                }
                case PREFILL_COMPLETED -> {
                    prefillDone.compareAndSet(0L, System.nanoTime());
                    prefillCompletedMemory.compareAndSet(null, sampleGpuMemory(gpuIndex));
                }
                case GENERATION_STARTED -> generationStartedMemory.compareAndSet(null, sampleGpuMemory(gpuIndex));
                default -> {
                }
            }
        }
    }

    private record MemorySample(int gpuIndex, long usedMb, long totalMb) {
        private long deltaUsedMb(MemorySample previous) {
            if (previous == null || previous.usedMb < 0 || usedMb < 0) return 0L;
            return usedMb - previous.usedMb;
        }

        private static MemorySample maxUsed(MemorySample... samples) {
            MemorySample max = null;
            for (MemorySample sample : samples) {
                if (sample == null || sample.usedMb < 0) continue;
                if (max == null || sample.usedMb > max.usedMb) {
                    max = sample;
                }
            }
            return max;
        }

        @Override
        public String toString() {
            if (usedMb < 0) return "n/a";
            return "gpu" + gpuIndex + "=" + usedMb + "/" + totalMb + " MiB";
        }
    }

    private static MemorySample sampleGpuMemory(int gpuIndex) {
        ProcessBuilder builder = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=index,memory.used,memory.total",
                "--format=csv,noheader,nounits"
        );
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 3) continue;
                    int index = Integer.parseInt(parts[0].trim());
                    if (index != gpuIndex) continue;
                    long used = Long.parseLong(parts[1].trim());
                    long total = Long.parseLong(parts[2].trim());
                    process.waitFor();
                    return new MemorySample(index, used, total);
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        return new MemorySample(gpuIndex, -1L, -1L);
    }
}
