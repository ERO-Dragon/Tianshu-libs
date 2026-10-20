package com.rheinmetal.tianshu.libs.llm;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The engines loaded by one server instance.
 *
 * <p>Either engine may be absent: a server configured with only an embedding model serves RAG vectors
 * and has no chat engine, and a server configured with only a chat model has no embedding engine. Only
 * the combination without any engine is rejected.
 */
public class ModelRegistry {

    /**
     * Message reported when chat inference is requested from a server that was started without a chat
     * model. Callers should probe {@link #hasChatEngine()} instead of matching this text.
     */
    public static final String CHAT_MODEL_NOT_CONFIGURED =
            "Chat model is not configured: this server was started without a chat model, so chat inference is unavailable";

    private final LlamaEngine chatEngine;
    private final EmbeddingEngine embeddingEngine;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    public ModelRegistry(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine) {
        if (chatEngine == null && embeddingEngine == null) {
            throw new IllegalArgumentException("At least one engine is required: configure a chat model, an embedding model, or both");
        }
        this.chatEngine = chatEngine;
        this.embeddingEngine = embeddingEngine;
    }

    /**
     * The chat engine.
     *
     * @throws IllegalStateException when this registry was created without a chat engine
     */
    public LlamaEngine getChatEngine() {
        LlamaEngine current = chatEngine;
        if (current == null) {
            throw new IllegalStateException(CHAT_MODEL_NOT_CONFIGURED);
        }
        return current;
    }

    /**
     * The embedding engine.
     *
     * @throws IllegalStateException when this registry was created without an embedding engine
     */
    public EmbeddingEngine getEmbeddingEngine() {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        return embeddingEngine;
    }

    /** True when a chat engine was configured; it says nothing about whether the model finished loading. */
    public boolean hasChatEngine() {
        return chatEngine != null;
    }

    public boolean hasEmbeddingEngine() {
        return embeddingEngine != null;
    }

    /** True when the chat model and any configured embedding model are loaded and usable. */
    public boolean isReady() {
        return chatEngine != null
                && chatEngine.isModelLoaded()
                && (embeddingEngine == null || embeddingEngine.isModelLoaded());
    }

    public boolean supportsEnableThinking() {
        return chatEngine != null && chatEngine.supportsEnableThinking();
    }

    public boolean supportsMtp() {
        return chatEngine != null && chatEngine.supportsMtp();
    }

    /** The chat model's MTP capability, or a non-supported capability when no chat engine is loaded. */
    public MtpCapability getMtpCapability() {
        return chatEngine == null ? new MtpCapability(false, 0, null) : chatEngine.getMtpCapability();
    }

    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) return;
        if (embeddingEngine != null) {
            embeddingEngine.shutdown();
        }
        if (chatEngine != null) {
            chatEngine.shutdown();
        }
    }
}
