package com.rheinmetal.tianshu.libs.llm;

import java.util.concurrent.atomic.AtomicBoolean;

public class ModelRegistry {

    private final LlamaEngine chatEngine;
    private final EmbeddingEngine embeddingEngine;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public ModelRegistry(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine) {
        if (chatEngine == null) {
            throw new IllegalArgumentException("Chat engine is required");
        }
        this.chatEngine = chatEngine;
        this.embeddingEngine = embeddingEngine;
    }

    public LlamaEngine getChatEngine() {
        return chatEngine;
    }

    public EmbeddingEngine getEmbeddingEngine() {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        return embeddingEngine;
    }

    public boolean hasEmbeddingEngine() {
        return embeddingEngine != null;
    }

    public boolean isReady() {
        return chatEngine.isModelLoaded() && (embeddingEngine == null || embeddingEngine.isModelLoaded());
    }

    public boolean supportsEnableThinking() {
        return chatEngine.supportsEnableThinking();
    }

    public boolean supportsMtp() {
        return chatEngine.supportsMtp();
    }

    public MtpCapability getMtpCapability() {
        return chatEngine.getMtpCapability();
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        if (embeddingEngine != null) {
            embeddingEngine.shutdown();
        }
        chatEngine.shutdown();
    }
}
