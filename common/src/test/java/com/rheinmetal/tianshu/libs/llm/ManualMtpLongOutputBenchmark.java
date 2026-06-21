package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.LlamaCppSamplerChain;
import org.argeo.jjml.llm.LlamaCppSamplers;
import org.argeo.jjml.llm.SpeculativeStats;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class ManualMtpLongOutputBenchmark {
    private static final String DEFAULT_MODEL = "D:\\AIPROJECT\\Qwen3.5-2B-Q4_K_M.gguf";
    private static final String CONTEXT_BLOCK = """
            Region note: The overworld base contains a storage hall, a villager trading floor, a redstone clock tower,
            a crop terrace, a copper roof under oxidation control, and a minecart line that passes through three chunk
            borders. The player wants safe automation without blocking villagers, breaking item filters, or creating
            lag spikes during combat. A skeleton farm sends bones into the sorter, a kelp farm sends fuel into the
            smelter, and a bamboo module is reserved for future scaffolding production.
            Task constraint: Keep entrances lit, preserve two-wide paths for villagers, never place lava near wooden
            stairs, keep water streams covered where mobs can pathfind, label overflow chests, and prefer reversible
            changes. If a route crosses a ravine, use slabs on the lower half and place torches every seventh block.
            Inventory note: Useful materials include stone bricks, spruce trapdoors, smooth basalt, powered rails,
            repeaters, comparators, hoppers, barrels, glass panes, moss carpets, candles, ladders, buckets, and spare
            shulker boxes. Avoid spending diamonds unless the plan directly improves survival or navigation.
            """;

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("tianshu.llm.model", DEFAULT_MODEL);
        int context = Integer.getInteger("tianshu.llm.context", 8192);
        int gpuLayers = Integer.getInteger("tianshu.llm.gpuLayers", 999);
        int threads = Integer.getInteger("tianshu.llm.threads", 4);
        int maxDraftMax = Integer.getInteger("tianshu.mtp.maxDraftMax", 8);
        int maxTokens = Integer.getInteger("tianshu.mtp.maxTokens", 1024);
        int targetPromptTokens = Integer.getInteger("tianshu.mtp.targetPromptTokens", 7000);
        int gpuIndex = Integer.getInteger("tianshu.llm.gpuIndex", 0);
        String device = System.getProperty("tianshu.llm.device", "");

        System.out.printf(
                "Manual MTP long-output benchmark%nmodel=%s%ncontext=%d, gpuLayers=%d, threads=%d, maxDraftMax=%d, maxTokens=%d, targetPromptTokens=%d, device=%s%n",
                model, context, gpuLayers, threads, maxDraftMax, maxTokens, targetPromptTokens, device
        );

        LlamaEngine engine = LlamaEngine.loadChatEngine(
                model,
                context,
                threads,
                gpuLayers,
                device.isBlank() ? null : device,
                "manual-mtp-long-output",
                "auto",
                1
        );
        try {
            LlamaCppModel loadedModel = engine.getModel();
            System.out.printf("supportsMtp=%s, mtpLayers=%d%n", engine.supportsMtp(), engine.getMtpLayerCount());
            if (!engine.supportsMtp()) return;

            String formattedPrompt = buildPrompt(loadedModel, targetPromptTokens, context, maxTokens, maxDraftMax);
            int promptTokens = loadedModel.getVocabulary().tokenize(formattedPrompt).remaining();
            int[] promptTokenIds = TokenIds.copyRemaining(loadedModel.getVocabulary().tokenize(formattedPrompt));
            System.out.println("promptTokens=" + promptTokens);

            for (int draftMax = 1; draftMax <= maxDraftMax; draftMax++) {
                MemorySample before = sampleGpuMemory(gpuIndex);
                long start = System.nanoTime();
                SpeculativeStats stats;
                int generated = 0;
                try (MtpTokenGenerator generator = new MtpTokenGenerator(
                        engine.createContext(InferenceLane.CHAT,
                                InferenceOptions.builder().mtpEnabled(true).mtpDraftMax(draftMax).build()),
                        buildIgnoreEosSampler(),
                        promptTokenIds,
                        draftMax)) {
                    while (generated < maxTokens) {
                        GeneratedToken token = generator.next(maxTokens - generated);
                        if (token == null) break;
                        generated++;
                    }
                    stats = generator.getSpeculativeStats();
                }
                long end = System.nanoTime();
                MemorySample after = sampleGpuMemory(gpuIndex);
                double wallSeconds = Math.max(0.001, (end - start) / 1_000_000_000.0);
                System.out.printf(
                        "draftMax=%d generated=%d wallTps=%.3f statsTps=%.3f acceptance=%.4f prompt=%d drafted=%d accepted=%d decodeMs=%.2f memBefore=%s memAfter=%s%n",
                        draftMax,
                        generated,
                        generated / wallSeconds,
                        stats.tokensPerSecond(),
                        stats.acceptanceRate(),
                        stats.promptTokens(),
                        stats.draftedTokens(),
                        stats.acceptedDraftTokens(),
                        stats.decodeNanos() / 1_000_000.0,
                        before,
                        after
                );
            }
        } finally {
            engine.shutdown();
        }
    }

    private static LlamaCppSamplerChain buildIgnoreEosSampler() {
        DefaultSamplerChainParams params = new DefaultSamplerChainParams(
                0.0f,
                0,
                0L,
                40,
                0.95f,
                0.05f,
                1.0f,
                1.0f,
                0.0f,
                1.0f,
                64,
                1.0f,
                0.0f,
                0.0f,
                false,
                true
        );
        return LlamaCppSamplers.newDefaultSampler(params);
    }

    private static String buildPrompt(LlamaCppModel model,
                                      int targetPromptTokens,
                                      int contextSize,
                                      int maxTokens,
                                      int maxDraftMax) {
        int maxRunnablePromptTokens = contextSize
                - maxTokens
                - org.argeo.jjml.llm.SpeculativeParams.requiredMtpTargetOutputs(maxDraftMax)
                - 64;
        if (maxRunnablePromptTokens < MtpCalibrationRequest.MIN_HEAVY_PROMPT_TOKENS) {
            throw new IllegalArgumentException("context is too small for long-output MTP benchmark");
        }
        int target = Math.min(targetPromptTokens, maxRunnablePromptTokens);
        StringBuilder context = new StringBuilder();
        String formattedPrompt = format(model, context.toString());
        int promptTokens = countTokens(model, formattedPrompt);
        int block = 1;
        while (promptTokens < target) {
            int previousLength = context.length();
            context.append("Log block ").append(block++).append(":\n")
                    .append(CONTEXT_BLOCK)
                    .append('\n');
            String candidate = format(model, context.toString());
            int candidateTokens = countTokens(model, candidate);
            if (candidateTokens > maxRunnablePromptTokens) {
                context.setLength(previousLength);
                break;
            }
            formattedPrompt = candidate;
            promptTokens = candidateTokens;
            if (block > 512) {
                throw new IllegalStateException("failed to build benchmark prompt");
            }
        }
        return formattedPrompt;
    }

    private static String format(LlamaCppModel model, String context) {
        List<LlamaCppChatMessage> messages = new ArrayList<>();
        messages.add(new LlamaCppChatMessage("system",
                "You are a benchmark writer. Continue until the caller's token budget is exhausted. "
                        + "Do not stop early. Do not include hidden reasoning."));
        messages.add(new LlamaCppChatMessage("user",
                "Context:\n" + context
                        + "\nWrite a long, detailed Minecraft operations report. "
                        + "Use many short paragraphs with numbered sections. "
                        + "Keep writing concrete recommendations until you run out of output budget."));
        return model.formatChatMessages(messages, org.argeo.jjml.llm.util.ThinkingMode.DISABLED);
    }

    private static int countTokens(LlamaCppModel model, String prompt) {
        return model.getVocabulary().tokenize(prompt).remaining();
    }

    private record MemorySample(int gpuIndex, long usedMb, long totalMb, long utilPercent) {
        @Override
        public String toString() {
            if (usedMb < 0) return "n/a";
            return "gpu" + gpuIndex + "=" + usedMb + "/" + totalMb + " MiB, util=" + utilPercent + "%";
        }
    }

    private static MemorySample sampleGpuMemory(int gpuIndex) {
        ProcessBuilder builder = new ProcessBuilder(
                "nvidia-smi",
                "--query-gpu=index,memory.used,memory.total,utilization.gpu",
                "--format=csv,noheader,nounits"
        );
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length < 4) continue;
                    int index = Integer.parseInt(parts[0].trim());
                    if (index != gpuIndex) continue;
                    long used = Long.parseLong(parts[1].trim());
                    long total = Long.parseLong(parts[2].trim());
                    long util = Long.parseLong(parts[3].trim());
                    process.waitFor();
                    return new MemorySample(index, used, total, util);
                }
            }
            process.waitFor();
        } catch (Exception ignored) {
        }
        return new MemorySample(gpuIndex, -1L, -1L, -1L);
    }
}
