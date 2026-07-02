package com.rheinmetal.tianshu.libs.llm;

public record LlmStreamFinish(StreamFinishType type, LlmTokenUsage usage, Throwable error, String thinkingContent) {
    public LlmStreamFinish(StreamFinishType type, LlmTokenUsage usage, Throwable error) {
        this(type, usage, error, "");
    }

    public LlmStreamFinish {
        if (type == null) throw new IllegalArgumentException("type is required");
        if (usage == null) throw new IllegalArgumentException("usage is required");
        thinkingContent = thinkingContent == null ? "" : thinkingContent;
    }
}
