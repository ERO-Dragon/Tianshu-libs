package com.rheinmetal.tianshu.libs.llm;

public record LlmRuntimeCapabilities(
        boolean ready,
        boolean supportsThinking,
        boolean supportsMtp,
        boolean supportsEmbeddedMtp,
        boolean externalMtpAvailable,
        int mtpLayerCount
) {
    public LlmRuntimeCapabilities {
        if (mtpLayerCount < 0) throw new IllegalArgumentException("mtpLayerCount cannot be negative");
    }

    public static LlmRuntimeCapabilities unavailable() {
        return new LlmRuntimeCapabilities(
                false,
                false,
                false,
                false,
                false,
                0
        );
    }
}
