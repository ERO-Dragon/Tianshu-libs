package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.FlashAttentionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @Test
    void contextSizeCanBeConfiguredOnceForChatAndTask() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .contextSize(8192)
                .build());
    }

    @Test
    void contextSizeRejectsInvalidValues() {
        assertThrows(ServerConfig.ConfigException.class, () -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .contextSize(128)
                .build());
    }

    @Test
    void deviceOptionsCanBeConfigured() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .device("1")
                .embeddingModel("embedding.gguf")
                .embeddingDevice("0")
                .build());
    }

    @Test
    void flashAttentionCanBeConfigured() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .flashAttention(FlashAttentionMode.DISABLED)
                .build());
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .flashAttention(null)
                .build());
    }

    @Test
    void chatModelIsOptionalWhenAnEmbeddingModelIsConfigured() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .embeddingModel("embedding.gguf")
                .embeddingContextSize(2048)
                .build());
    }

    @Test
    void embeddingModelIsOptionalWhenAChatModelIsConfigured() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .build());
    }

    @Test
    void configurationWithoutAnyModelIsRejected() {
        ServerConfig.ConfigException error = assertThrows(ServerConfig.ConfigException.class,
                () -> JavaLlamaServer.builder().build());
        assertTrue(error.getMessage().contains("modelPath or embeddingModelPath"),
                "validation error must name both model paths, got: " + error.getMessage());
    }

    @Test
    void blankModelPathsAreRejectedAsMissing() {
        ServerConfig config = new ServerConfig();
        config.modelPath = "  ";
        config.embeddingModelPath = "";
        ServerConfig.ConfigException error = assertThrows(ServerConfig.ConfigException.class,
                () -> ServerConfig.validateOrThrow(config));
        assertTrue(error.getMessage().contains("modelPath or embeddingModelPath"),
                "blank paths must not satisfy the model requirement, got: " + error.getMessage());
    }

    @Test
    void otherValidationStillAppliesWithoutAChatModel() {
        ServerConfig config = new ServerConfig();
        config.embeddingModelPath = "embedding.gguf";
        config.embeddingContextSize = 128;
        ServerConfig.ConfigException error = assertThrows(ServerConfig.ConfigException.class,
                () -> ServerConfig.validateOrThrow(config));
        assertTrue(error.getMessage().contains("embeddingContextSize"),
                "unrelated range checks must still run, got: " + error.getMessage());
    }
}
