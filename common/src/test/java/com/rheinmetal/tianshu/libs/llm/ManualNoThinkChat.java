package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ManualNoThinkChat {
    private static final String DEFAULT_MODEL =
            "D:\\Minecraft\\.minecraft\\versions\\Tianshu\\config\\TianshuAIAssistant\\models\\llm\\Qwen3-4B\\Qwen3-4B-Q4_K_M.gguf";

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("tianshu.llm.model", DEFAULT_MODEL);
        int context = Integer.getInteger("tianshu.llm.context", 8192);
        int maxTurns = Integer.getInteger("tianshu.llm.maxTurns", 6);

        JavaLlamaServer service = JavaLlamaServer.builder()
                .model(model)
                .modelAlias("manual-no-think")
                .modelProfile("qwen3")
                .contextSize(context)
                .chatThreads(2)
                .chatMaxQueueSize(4)
                .taskThreads(2)
                .gpuLayers(999)
                .requestTimeoutSeconds(300)
                .build();

        SamplerConfig sampler = new SamplerConfig();
        sampler.setTemperature(0.0f);
        sampler.setEnableThinking(false);

        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("You are a concise Minecraft assistant. Reply directly. Do not reason step by step."));

        System.out.println("Loading model: " + model);
        service.start();
        System.out.println("Ready. no-think chat, context=" + context + ", gpuLayers=999");
        System.out.println("Type your message, /clear to clear chat history, /exit to quit.");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\nYou> ");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine();
                if (input == null) break;
                input = input.trim();
                if (input.isEmpty()) continue;
                if ("/exit".equalsIgnoreCase(input)) break;
                if ("/clear".equalsIgnoreCase(input)) {
                    history.clear();
                    history.add(ChatMessage.system("You are a concise Minecraft assistant. Reply directly. Do not reason step by step."));
                    System.out.println("History cleared.");
                    continue;
                }

                trimHistory(history, maxTurns);
                history.add(ChatMessage.user(input));

                AtomicInteger chunks = new AtomicInteger();
                AtomicLong firstChunkNanos = new AtomicLong(0L);
                long startNanos = System.nanoTime();
                StringBuilder response = new StringBuilder();

                System.out.print("AI> ");
                service.chatStream(history, sampler, chunk -> {
                    if (firstChunkNanos.compareAndSet(0L, System.nanoTime())) {
                        double ttftMs = (firstChunkNanos.get() - startNanos) / 1_000_000.0;
                        System.out.printf("[TTFT %.2f ms] ", ttftMs);
                    }
                    chunks.incrementAndGet();
                    response.append(chunk);
                    System.out.print(chunk);
                    System.out.flush();
                }).get();
                long endNanos = System.nanoTime();

                history.add(ChatMessage.assistant(response.toString()));
                double totalMs = (endNanos - startNanos) / 1_000_000.0;
                double genSeconds = firstChunkNanos.get() == 0L
                        ? totalMs / 1000.0
                        : Math.max(0.001, (endNanos - firstChunkNanos.get()) / 1_000_000_000.0);
                double chunksPerSecond = chunks.get() / genSeconds;
                System.out.printf("%n[total %.2f ms, chunks %d, chunks/sec %.2f]%n",
                        totalMs, chunks.get(), chunksPerSecond);
            }
        } finally {
            service.shutdown();
        }
    }

    private static void trimHistory(List<ChatMessage> history, int maxTurns) {
        int maxMessages = Math.max(1, maxTurns) * 2 + 1;
        while (history.size() > maxMessages) {
            history.remove(1);
        }
    }
}
