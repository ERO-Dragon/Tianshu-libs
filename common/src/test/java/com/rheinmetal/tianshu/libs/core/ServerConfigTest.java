package com.rheinmetal.tianshu.libs.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerConfigTest {
    @Test
    void taskHotSuspendSlotsCanBeZero() {
        assertDoesNotThrow(() -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .taskMaxQueueSize(0)
                .build());
    }

    @Test
    void taskHotSuspendSlotsAreCappedAtFive() {
        assertThrows(ServerConfig.ConfigException.class, () -> JavaLlamaServer.builder()
                .model("dummy.gguf")
                .taskMaxQueueSize(6)
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
}
