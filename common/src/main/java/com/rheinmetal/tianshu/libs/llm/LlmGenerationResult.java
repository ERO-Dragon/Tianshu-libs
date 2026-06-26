package com.rheinmetal.tianshu.libs.llm;

public record LlmGenerationResult(String text, LlmTokenUsage usage) {
    public LlmGenerationResult {
        if (text == null) throw new IllegalArgumentException("text is required");
        if (usage == null) throw new IllegalArgumentException("usage is required");
    }
}
