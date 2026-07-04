package com.rheinmetal.tianshu.libs.llm;

public record LlmTokenUsage(int promptTokens, int completionTokens, int thinkingTokens) {
    public LlmTokenUsage(int promptTokens, int completionTokens) {
        this(promptTokens, completionTokens, 0);
    }

    public LlmTokenUsage {
        if (promptTokens < 0) throw new IllegalArgumentException("promptTokens cannot be negative");
        if (completionTokens < 0) throw new IllegalArgumentException("completionTokens cannot be negative");
        if (thinkingTokens < 0) throw new IllegalArgumentException("thinkingTokens cannot be negative");
    }

    public int outputTokens() {
        return completionTokens + thinkingTokens;
    }

    public int totalTokens() {
        return promptTokens + outputTokens();
    }
}
