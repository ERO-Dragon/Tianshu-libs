package com.rheinmetal.tianshu.libs.llm;

public final class MtpCalibrationRequest {
    public static final int AUTO_MAX_DRAFT_MAX = 0;
    public static final int DEFAULT_MAX_DRAFT_MAX = AUTO_MAX_DRAFT_MAX;
    public static final int DEFAULT_AUTO_DRAFT_MAX_INITIAL_LIMIT = 8;
    public static final int DEFAULT_AUTO_DRAFT_MAX_LIMIT = 16;
    public static final int MAX_CALIBRATION_DRAFT_MAX = 32;
    public static final int DEFAULT_MAX_TOKENS = 256;
    public static final int DEFAULT_TARGET_PROMPT_TOKENS = 8192;
    public static final int MIN_HEAVY_PROMPT_TOKENS = 4096;
    private static final int MAX_TOKENS = 2048;
    private static final int MAX_TARGET_PROMPT_TOKENS = 65536;

    private final int maxDraftMax;
    private final int maxTokens;
    private final int targetPromptTokens;

    public MtpCalibrationRequest(int maxDraftMax, int maxTokens) {
        this(maxDraftMax, maxTokens, DEFAULT_TARGET_PROMPT_TOKENS);
    }

    public MtpCalibrationRequest(int maxDraftMax, int maxTokens, int targetPromptTokens) {
        if (maxDraftMax < AUTO_MAX_DRAFT_MAX || maxDraftMax > MAX_CALIBRATION_DRAFT_MAX) {
            throw new IllegalArgumentException("maxDraftMax must be 0 for auto or between 1 and "
                    + MAX_CALIBRATION_DRAFT_MAX);
        }
        if (maxTokens < 1 || maxTokens > MAX_TOKENS) {
            throw new IllegalArgumentException("maxTokens must be between 1 and " + MAX_TOKENS);
        }
        if (targetPromptTokens < MIN_HEAVY_PROMPT_TOKENS || targetPromptTokens > MAX_TARGET_PROMPT_TOKENS) {
            throw new IllegalArgumentException("targetPromptTokens must be between "
                    + MIN_HEAVY_PROMPT_TOKENS + " and " + MAX_TARGET_PROMPT_TOKENS);
        }
        this.maxDraftMax = maxDraftMax;
        this.maxTokens = maxTokens;
        this.targetPromptTokens = targetPromptTokens;
    }

    public static MtpCalibrationRequest defaults() {
        return new MtpCalibrationRequest(DEFAULT_MAX_DRAFT_MAX, DEFAULT_MAX_TOKENS);
    }

    public int getMaxDraftMax() { return maxDraftMax; }
    public int getMaxTokens() { return maxTokens; }
    public int getTargetPromptTokens() { return targetPromptTokens; }
    public boolean isAutoDraftMax() { return maxDraftMax == AUTO_MAX_DRAFT_MAX; }
}
