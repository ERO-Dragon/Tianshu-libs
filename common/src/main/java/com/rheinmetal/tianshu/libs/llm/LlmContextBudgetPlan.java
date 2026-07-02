package com.rheinmetal.tianshu.libs.llm;

public record LlmContextBudgetPlan(
        int requestedContextSize,
        int trainingContextSize,
        int memoryContextSize,
        int plannedContextSize,
        int promptTokenBudget,
        int promptMarginTokens,
        long safetyMarginBytes,
        boolean reliable,
        String limitation
) {
    public LlmContextBudgetPlan {
        limitation = limitation == null ? "" : limitation.trim();
        if (reliable) {
            requireNonNegative(requestedContextSize, "requestedContextSize");
            requireAtLeastMinusOne(trainingContextSize, "trainingContextSize");
            requireAtLeastMinusOne(memoryContextSize, "memoryContextSize");
            requireNonNegative(plannedContextSize, "plannedContextSize");
            requireNonNegative(promptTokenBudget, "promptTokenBudget");
            requireNonNegative(promptMarginTokens, "promptMarginTokens");
            requireNonNegative(safetyMarginBytes, "safetyMarginBytes");
            if (plannedContextSize > requestedContextSize) {
                throw new IllegalArgumentException("plannedContextSize cannot exceed requestedContextSize");
            }
            int expectedPromptBudget = Math.max(0, plannedContextSize - promptMarginTokens);
            if (promptTokenBudget != expectedPromptBudget) {
                throw new IllegalArgumentException("promptTokenBudget must equal plannedContextSize - promptMarginTokens");
            }
        }
    }

    public static LlmContextBudgetPlan unavailable(String limitation) {
        return new LlmContextBudgetPlan(
                -1,
                -1,
                -1,
                -1,
                -1,
                -1,
                -1L,
                false,
                limitation
        );
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) throw new IllegalArgumentException(name + " cannot be negative");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) throw new IllegalArgumentException(name + " cannot be negative");
    }

    private static void requireAtLeastMinusOne(int value, String name) {
        if (value < -1) throw new IllegalArgumentException(name + " cannot be less than -1");
    }
}
