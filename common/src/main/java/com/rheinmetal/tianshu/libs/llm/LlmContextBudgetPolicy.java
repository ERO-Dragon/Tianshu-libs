package com.rheinmetal.tianshu.libs.llm;

public record LlmContextBudgetPolicy(int promptMarginTokens, long safetyMarginBytes) {
    public static final int DEFAULT_PROMPT_MARGIN_TOKENS = 64;
    public static final long DEFAULT_SAFETY_MARGIN_BYTES = 512L * 1024L * 1024L;

    public LlmContextBudgetPolicy {
        if (promptMarginTokens < 0) {
            throw new IllegalArgumentException("promptMarginTokens cannot be negative");
        }
        if (safetyMarginBytes < 0L) {
            throw new IllegalArgumentException("safetyMarginBytes cannot be negative");
        }
    }

    public static LlmContextBudgetPolicy defaults() {
        return new LlmContextBudgetPolicy(
                DEFAULT_PROMPT_MARGIN_TOKENS,
                DEFAULT_SAFETY_MARGIN_BYTES
        );
    }
}
