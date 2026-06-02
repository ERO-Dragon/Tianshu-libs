package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceTask;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import com.rheinmetal.tianshu.libs.rag.VectorSearch;
import org.argeo.jjml.llm.LlamaCppChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

public class LibsApi {

    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;
    private static final int DEFAULT_TOP_K = 4;
    private static final float DEFAULT_THRESHOLD = 0.7f;

    private final LlamaEngine chatEngine;
    private final EmbeddingEngine embeddingEngine;
    private final VectorSearch vectorSearch;
    private final int requestTimeoutSeconds;

    public LibsApi(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine) {
        this(chatEngine, embeddingEngine, DEFAULT_REQUEST_TIMEOUT_SECONDS);
    }

    public LibsApi(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine, int requestTimeoutSeconds) {
        if (chatEngine == null) {
            throw new IllegalArgumentException("Chat engine is required");
        }
        this.chatEngine = chatEngine;
        this.embeddingEngine = embeddingEngine;
        this.vectorSearch = new VectorSearch();
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public boolean isReady() {
        return chatEngine.isModelLoaded() && (embeddingEngine == null || embeddingEngine.isModelLoaded());
    }

    public boolean hasChatQueueCapacity() {
        return chatEngine.hasQueueCapacity(InferenceLane.CHAT);
    }

    public boolean hasTaskQueueCapacity() {
        return chatEngine.hasQueueCapacity(InferenceLane.TASK);
    }

    public void shutdown() {
        chatEngine.shutdown();
        if (embeddingEngine != null) {
            embeddingEngine.shutdown();
        }
    }

    public String chat(List<ChatMessage> messages) throws Exception {
        return chat(messages, null, 0);
    }

    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        return doSyncChat(InferenceLane.CHAT, messages, sampler, maxTokens);
    }

    public void chatStream(List<ChatMessage> messages, Consumer<String> onToken) throws Exception {
        chatStream(messages, null, onToken);
    }

    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        doStreamChat(InferenceLane.CHAT, messages, sampler, 0, 0, false, onToken).get(requestTimeoutSeconds, TimeUnit.SECONDS);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages) {
        return task(messages, null, 0, 0, false);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                           SamplerConfig sampler,
                                           int maxTokens,
                                           int priority,
                                           boolean preemptible) {
        return doAsyncChat(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                           SamplerConfig sampler,
                                           int maxTokens,
                                           int priority,
                                           boolean preemptible,
                                           Consumer<String> tokenConsumer) {
        return doStreamChat(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, tokenConsumer);
    }

    public float[] embed(String text) throws Exception {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        return embeddingEngine.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        return embeddingEngine.embed(texts);
    }

    public List<RagSearchResult> search(String queryText, List<String> texts) {
        return search(queryText, texts, DEFAULT_TOP_K, DEFAULT_THRESHOLD);
    }

    public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        try {
            float[] queryVector = embeddingEngine.embed(queryText);
            float[][] textVectors = embeddingEngine.embed(texts);
            List<RagSearchResult> allResults = vectorSearch.searchWithQuery(queryVector, texts, textVectors, topK);
            List<RagSearchResult> filtered = new ArrayList<>();
            for (RagSearchResult result : allResults) {
                if (result.score >= threshold) {
                    filtered.add(result);
                }
            }
            return filtered;
        } catch (Exception e) {
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    private String doSyncChat(InferenceLane lane, List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        if (!chatEngine.hasQueueCapacity(lane)) {
            throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
        }
        List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
        InferenceTask task = InferenceTask.syncChat(lane, llamaMessages, sampler, maxTokens, 0, false);
        chatEngine.submitTask(task);
        return task.getSyncFuture().get(requestTimeoutSeconds, TimeUnit.SECONDS);
    }

    private CompletableFuture<String> doStreamChat(InferenceLane lane,
                                                    List<ChatMessage> messages,
                                                    SamplerConfig sampler,
                                                    int maxTokens,
                                                    int priority,
                                                    boolean preemptible,
                                                    Consumer<String> tokenConsumer) {
        try {
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.stream(lane, llamaMessages, sampler, maxTokens, priority, preemptible, tokenConsumer);
            chatEngine.submitTask(task);
            return task.getSyncFuture();
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private CompletableFuture<String> doAsyncChat(InferenceLane lane,
                                                   List<ChatMessage> messages,
                                                   SamplerConfig sampler,
                                                   int maxTokens,
                                                   int priority,
                                                   boolean preemptible) {
        try {
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.syncChat(lane, llamaMessages, sampler, maxTokens, priority, preemptible);
            chatEngine.submitTask(task);
            return task.getSyncFuture();
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private List<LlamaCppChatMessage> convertMessages(List<ChatMessage> messages) {
        List<LlamaCppChatMessage> result = new ArrayList<>();
        if (messages == null) return result;
        for (ChatMessage msg : messages) {
            if (msg == null || msg.role == null || msg.content == null) continue;
            result.add(new LlamaCppChatMessage(msg.role, msg.content));
        }
        return result;
    }
}