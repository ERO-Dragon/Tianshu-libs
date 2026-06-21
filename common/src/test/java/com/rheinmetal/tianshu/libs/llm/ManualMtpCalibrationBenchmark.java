package com.rheinmetal.tianshu.libs.llm;

import com.rheinmetal.tianshu.libs.core.JavaLlamaServer;

public class ManualMtpCalibrationBenchmark {
    private static final String DEFAULT_MODEL = "D:\\AIPROJECT\\Qwen3.5-2B-Q4_K_M.gguf";

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("tianshu.llm.model", DEFAULT_MODEL);
        int context = Integer.getInteger("tianshu.llm.context", 8192);
        int gpuLayers = Integer.getInteger("tianshu.llm.gpuLayers", 999);
        int chatThreads = Integer.getInteger("tianshu.llm.chatThreads", 4);
        int taskThreads = Integer.getInteger("tianshu.llm.taskThreads", chatThreads);
        int maxDraftMax = Integer.getInteger("tianshu.mtp.maxDraftMax", MtpCalibrationRequest.DEFAULT_AUTO_DRAFT_MAX_LIMIT);
        int maxTokens = Integer.getInteger("tianshu.mtp.maxTokens", MtpCalibrationRequest.DEFAULT_MAX_TOKENS);
        int targetPromptTokens = Integer.getInteger("tianshu.mtp.targetPromptTokens", MtpCalibrationRequest.DEFAULT_TARGET_PROMPT_TOKENS);
        String device = System.getProperty("tianshu.llm.device", "");

        System.out.printf(
                "Manual MTP calibration benchmark%nmodel=%s%ncontext=%d, gpuLayers=%d, chatThreads=%d, taskThreads=%d, maxDraftMax=%d, maxTokens=%d, targetPromptTokens=%d%n",
                model, context, gpuLayers, chatThreads, taskThreads, maxDraftMax, maxTokens, targetPromptTokens
        );

        JavaLlamaServer.Builder builder = JavaLlamaServer.builder()
                .model(model)
                .modelAlias("manual-mtp-calibration")
                .modelProfile("auto")
                .contextSize(context)
                .chatThreads(chatThreads)
                .chatMaxQueueSize(1)
                .taskThreads(taskThreads)
                .gpuLayers(gpuLayers)
                .requestTimeoutSeconds(1800);
        if (!device.isBlank()) {
            builder.device(device);
        }

        JavaLlamaServer service = builder.build();
        service.start();
        try {
            System.out.printf("supportsMtp=%s, capability.layers=%d%n",
                    service.supportsMtp(),
                    service.getMtpCapability().getMtpLayerCount());
            MtpCalibrationResult result = service.calibrateMtp(
                    new MtpCalibrationRequest(maxDraftMax, maxTokens, targetPromptTokens)
            );
            System.out.println("message=" + result.getMessage());
            System.out.printf("supported=%s, tested=%d, bestDraftMax=%d%n",
                    result.isSupported(),
                    result.getMaxDraftMaxTested(),
                    result.getBestDraftMax());
            for (MtpTrialResult trial : result.getTrials()) {
                System.out.printf(
                        "draftMax=%d success=%s tps=%.3f acceptance=%.4f prompt=%d generated=%d drafted=%d accepted=%d decodeMs=%.2f error=%s%n",
                        trial.getDraftMax(),
                        trial.isSuccess(),
                        trial.getTokensPerSecond(),
                        trial.getAcceptanceRate(),
                        trial.getPromptTokens(),
                        trial.getGeneratedTokens(),
                        trial.getDraftedTokens(),
                        trial.getAcceptedDraftTokens(),
                        trial.getDecodeNanos() / 1_000_000.0,
                        trial.getErrorMessage()
                );
            }
        } finally {
            service.shutdown();
        }
    }
}
