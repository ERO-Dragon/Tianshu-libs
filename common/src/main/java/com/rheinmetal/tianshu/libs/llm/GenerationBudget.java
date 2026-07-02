package com.rheinmetal.tianshu.libs.llm;

final class GenerationBudget {
    private GenerationBudget() {
    }

    static int resolveCompletionLimit(int requestedMaxTokens,
                                      int contextSize,
                                      int promptTokens,
                                      LlmContextBudgetPolicy policy) {
        if (promptTokens < 0) {
            throw new IllegalArgumentException("promptTokens cannot be negative");
        }
        LlmContextBudgetPolicy effectivePolicy = policy == null ? LlmContextBudgetPolicy.defaults() : policy;
        if (contextSize <= 0) {
            return requestedMaxTokens > 0 ? requestedMaxTokens : 0;
        }
        int contextRemainder = Math.max(0, contextSize - promptTokens - effectivePolicy.promptMarginTokens());
        if (requestedMaxTokens > 0) {
            return Math.min(requestedMaxTokens, contextRemainder);
        }
        return contextRemainder;
    }
}
