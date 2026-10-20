package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts a server with only an embedding model and checks that it really serves vectors while the chat
 * surface fails with a readable error.
 *
 * <p>Runs only when {@code -Dtianshu.embedding.model=<path to an embedding GGUF>} is supplied, matching
 * the convention of {@code LlamaEngineInterruptIntegrationTest}.
 */
@EnabledIfSystemProperty(named = "tianshu.embedding.model", matches = ".+")
class EmbeddingOnlyStartupIntegrationTest {
    private JavaLlamaServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void embeddingOnlyServerStartsAndServesVectors() throws Exception {
        Path embeddingModel = Path.of(System.getProperty("tianshu.embedding.model"));
        assertTrue(Files.isRegularFile(embeddingModel), "embedding model not found: " + embeddingModel);

        server = JavaLlamaServer.builder()
                .embeddingModel(embeddingModel.toString())
                .embeddingContextSize(2048)
                .embeddingThreads(2)
                .embeddingGpuLayers(0)
                .build();

        server.start();

        assertTrue(server.isStarted(), "an embedding-only configuration must start successfully");
        assertTrue(server.hasEmbeddingEngine(), "the embedding engine must be loaded");
        assertFalse(server.hasChatEngine(), "no chat model is configured");
        assertFalse(server.isReady(), "isReady() reports chat availability only");

        float[] queryVector = server.embed("the player is building a stone tower");
        assertTrue(queryVector.length > 0, "embedding must return a vector");

        float[][] batchVectors = server.embed(List.of("first text", "second text"));
        assertEquals(2, batchVectors.length, "batch embedding must return one vector per text");

        List<RagSearchResult> results = server.search(
                "stone tower",
                List.of("the player is building a stone tower", "a cow is eating grass"),
                2,
                -1.0f
        );
        assertNotNull(results);
        assertTrue(results.size() >= 2, "local RAG search must still rank the supplied texts");

        CompletableFuture<String> chat = server.chat(List.of(ChatMessage.user("ping")), null, 16);
        ExecutionException chatFailure = assertThrows(ExecutionException.class, () -> chat.get(10, TimeUnit.SECONDS));
        assertTrue(chatFailure.getCause() instanceof IllegalStateException,
                "chat must fail with a state error, got: " + chatFailure.getCause());
        assertTrue(chatFailure.getCause().getMessage().contains("Chat model is not configured"),
                chatFailure.getCause().getMessage());

        IllegalStateException tokenError = assertThrows(IllegalStateException.class,
                () -> server.countChatPromptTokens(List.of(ChatMessage.user("ping")), null));
        assertTrue(tokenError.getMessage().contains("Chat model is not configured"), tokenError.getMessage());

        assertFalse(server.getRuntimeCapabilities().ready());
        assertFalse(server.getContextBudgetPlan().reliable());
        assertEquals(0, server.getChatQueueSize());

        server.shutdown();
        server.shutdown();
        assertFalse(server.isStarted());
        assertFalse(server.hasEmbeddingEngine());
    }
}
