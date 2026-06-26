package com.rheinmetal.tianshu.libs.llm;

public record LlmTokenUsage(int promptTokens, int completionTokens) {
    public LlmTokenUsage {
        if (promptTokens < 0) throw new IllegalArgumentException("promptTokens cannot be negative");
        if (completionTokens < 0) throw new IllegalArgumentException("completionTokens cannot be negative");
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }
}
