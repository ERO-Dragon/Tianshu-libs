package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.FlashAttentionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
