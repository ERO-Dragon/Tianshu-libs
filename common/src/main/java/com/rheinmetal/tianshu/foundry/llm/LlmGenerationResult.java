package com.rheinmetal.tianshu.foundry.llm;

public record LlmGenerationResult(String text, LlmTokenUsage usage, String thinkingContent) {
    public LlmGenerationResult(String text, LlmTokenUsage usage) {
        this(text, usage, "");
    }

    public LlmGenerationResult {
        if (text == null) throw new IllegalArgumentException("text is required");
        if (usage == null) throw new IllegalArgumentException("usage is required");
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
    }
}
