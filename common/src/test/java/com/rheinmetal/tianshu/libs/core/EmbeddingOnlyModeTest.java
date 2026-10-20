package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.ModelRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour that must not depend on a chat engine being present.
 *
 * <p>Covers the API surface reachable without loading a GGUF. The started embedding-only path is
 * covered by {@link EmbeddingOnlyStartupIntegrationTest}, which needs a real embedding model.
 */
class EmbeddingOnlyModeTest {

    @Test
    void enginePairRequiresAtLeastOneEngine() {
        IllegalArgumentException libsError = assertThrows(IllegalArgumentException.class,
                () -> new LibsApi(null, null));
        assertTrue(libsError.getMessage().contains("At least one engine"), libsError.getMessage());

        IllegalArgumentException registryError = assertThrows(IllegalArgumentException.class,
                () -> new ModelRegistry(null, null));
        assertTrue(registryError.getMessage().contains("At least one engine"), registryError.getMessage());
    }

    @Test
    void dryRunContextBudgetRequiresAChatModel() {
        ServerConfig config = new ServerConfig();
        config.embeddingModelPath = "embedding.gguf";

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> JavaLlamaServer.dryRunContextBudget(config));
        assertTrue(error.getMessage().contains("Chat model is not configured"), error.getMessage());

        IllegalStateException builderError = assertThrows(IllegalStateException.class,
                () -> JavaLlamaServer.builder().embeddingModel("embedding.gguf").dryRunContextBudget());
        assertTrue(builderError.getMessage().contains("Chat model is not configured"), builderError.getMessage());
    }

    @Test
    void chatCapabilityQueriesStaySafeWithoutAChatModel() {
        JavaLlamaServer server = JavaLlamaServer.builder()
                .embeddingModel("embedding.gguf")
                .build();

        assertFalse(server.supportsEnableThinking());
        assertFalse(server.supportsMtp());
        assertFalse(server.getMtpCapability().isSupported());
        assertFalse(server.getRuntimeCapabilities().ready());
        assertFalse(server.getContextBudgetPlan().reliable());
        assertFalse(server.getContextBudgetPlan(InferenceLane.CHAT).reliable());
        assertFalse(server.hasChatQueueCapacity());
        assertFalse(server.hasTaskQueueCapacity());
        assertEquals(0, server.getChatQueueSize());
    }

    @Test
    void stateQueriesDistinguishNotStartedFromChatUnavailable() {
        JavaLlamaServer server = JavaLlamaServer.builder()
                .embeddingModel("embedding.gguf")
                .build();

        assertFalse(server.isStarted());
        assertFalse(server.isReady());
        assertFalse(server.hasChatEngine());
        assertFalse(server.hasEmbeddingEngine());
    }

    @Test
    void chatBeforeStartFailsWithAReadableStateError() {
        JavaLlamaServer server = JavaLlamaServer.builder()
                .embeddingModel("embedding.gguf")
                .build();

        IllegalStateException chatError = assertThrows(IllegalStateException.class,
                () -> server.chat("hello", "you are a test"));
        assertTrue(chatError.getMessage().contains("not started"), chatError.getMessage());

        IllegalStateException embedError = assertThrows(IllegalStateException.class,
                () -> server.embed("hello"));
        assertTrue(embedError.getMessage().contains("not started"), embedError.getMessage());

        assertThrows(IllegalStateException.class,
                () -> server.countChatPromptTokens(List.of(ChatMessage.user("hello")), null));

        assertThrows(IllegalStateException.class, server::calibrateMtpAsync);
    }

    @Test
    void shutdownIsSafeWithoutAnyStartedEngine() {
        JavaLlamaServer server = JavaLlamaServer.builder()
                .embeddingModel("embedding.gguf")
                .build();

        assertDoesNotThrow(server::shutdown);
        assertDoesNotThrow(server::shutdown);
        assertFalse(server.isStarted());
        assertFalse(server.hasChatEngine());
        assertFalse(server.hasEmbeddingEngine());
    }
}
