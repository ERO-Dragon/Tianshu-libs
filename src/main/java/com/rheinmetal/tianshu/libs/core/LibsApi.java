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
    private static final int MAX_EMBEDDING_TEXT_LENGTH = 8192;
    private static final int MAX_EMBEDDING_BATCH_SIZE = 100;

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

    private static class StreamResult {
        final InferenceTask task;
        final CompletableFuture<String> future;

        StreamResult(InferenceTask task, CompletableFuture<String> future) {
            this.task = task;
            this.future = future;
        }
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
        StreamResult result = doStreamChat(InferenceLane.CHAT, messages, sampler, 0, 0, false, onToken);
        try {
            result.future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            chatEngine.cancelTask(result.task);
            throw new Exception("Request timeout after " + requestTimeoutSeconds + " seconds");
        }
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
        return doStreamChat(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, tokenConsumer).future;
    }

    public float[] embed(String text) throws Exception {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text cannot be null or blank");
        }
        if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
            throw new IllegalArgumentException("text exceeds maximum length of " + MAX_EMBEDDING_TEXT_LENGTH + " characters");
        }
        return embeddingEngine.embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        if (embeddingEngine == null) {
            throw new IllegalStateException("Embedding engine is not configured");
        }
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts cannot be null or empty");
        }
        if (texts.size() > MAX_EMBEDDING_BATCH_SIZE) {
            throw new IllegalArgumentException("batch size exceeds maximum of " + MAX_EMBEDDING_BATCH_SIZE);
        }
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text at index " + i + " cannot be null or blank");
            }
            if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
                throw new IllegalArgumentException("text at index " + i + " exceeds maximum length of " + MAX_EMBEDDING_TEXT_LENGTH + " characters");
            }
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
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be positive");
        }
        if (Float.isNaN(threshold) || threshold < -1.0f || threshold > 1.0f) {
            throw new IllegalArgumentException("threshold must be between -1.0 and 1.0");
        }
        try {
            List<String> searchableTexts = filterSearchableTexts(texts);
            if (searchableTexts.isEmpty()) {
                return List.of();
            }
            int expectedDimension = embeddingEngine.getEmbeddingSize();
            float[] queryVector = embeddingEngine.embed(queryText);
            if (queryVector.length != expectedDimension) {
                throw new IllegalArgumentException("Query vector dimension " + queryVector.length + " does not match embedding model dimension " + expectedDimension);
            }
            float[][] textVectors = embeddingEngine.embed(searchableTexts);
            if (textVectors.length != searchableTexts.size()) {
                throw new IllegalArgumentException("Embedding model returned " + textVectors.length + " vectors for " + searchableTexts.size() + " texts");
            }
            for (int i = 0; i < textVectors.length; i++) {
                if (textVectors[i].length != expectedDimension) {
                    throw new IllegalArgumentException("Document vector at index " + i + " has dimension " + textVectors[i].length + " which does not match embedding model dimension " + expectedDimension);
                }
            }
            List<RagSearchResult> allResults = vectorSearch.searchWithQuery(queryVector, searchableTexts, textVectors, topK);
            List<RagSearchResult> filtered = new ArrayList<>();
            for (RagSearchResult result : allResults) {
                if (result.score >= threshold) {
                    filtered.add(result);
                }
            }
            return filtered;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Search failed: " + e.getMessage(), e);
        }
    }

    private String doSyncChat(InferenceLane lane, List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        validateMaxTokens(maxTokens);
        if (!chatEngine.hasQueueCapacity(lane)) {
            throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
        }
        List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
        InferenceTask task = InferenceTask.syncChat(lane, llamaMessages, sampler, maxTokens, 0, false);
        chatEngine.submitTask(task);
        try {
            return task.getSyncFuture().get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            chatEngine.cancelTask(task);
            throw new Exception("Request timeout after " + requestTimeoutSeconds + " seconds");
        }
    }

    private StreamResult doStreamChat(InferenceLane lane,
                                                    List<ChatMessage> messages,
                                                    SamplerConfig sampler,
                                                    int maxTokens,
                                                    int priority,
                                                    boolean preemptible,
                                                    Consumer<String> tokenConsumer) {
        try {
            validateMaxTokens(maxTokens);
            if (tokenConsumer == null) {
                throw new IllegalArgumentException("tokenConsumer is required");
            }
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.stream(lane, llamaMessages, sampler, maxTokens, priority, preemptible, tokenConsumer);
            chatEngine.submitTask(task);
            return new StreamResult(task, cancellableFuture(task));
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return new StreamResult(null, failed);
        }
    }

    private CompletableFuture<String> doAsyncChat(InferenceLane lane,
                                                   List<ChatMessage> messages,
                                                   SamplerConfig sampler,
                                                   int maxTokens,
                                                   int priority,
                                                   boolean preemptible) {
        try {
            validateMaxTokens(maxTokens);
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.syncChat(lane, llamaMessages, sampler, maxTokens, priority, preemptible);
            chatEngine.submitTask(task);
            return cancellableFuture(task);
        } catch (Exception e) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private CompletableFuture<String> cancellableFuture(InferenceTask task) {
        CompletableFuture<String> exposed = new CompletableFuture<>();
        task.getSyncFuture().whenComplete((result, error) -> {
            if (error != null) {
                exposed.completeExceptionally(error);
            } else {
                exposed.complete(result);
            }
        });
        exposed.whenComplete((result, error) -> {
            if (exposed.isCancelled() && !task.getSyncFuture().isDone()) {
                chatEngine.cancelTask(task);
            }
        });
        return exposed;
    }

    private List<LlamaCppChatMessage> convertMessages(List<ChatMessage> messages) {
        List<LlamaCppChatMessage> result = new ArrayList<>();
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be null or empty");
        }
        for (ChatMessage msg : messages) {
            if (msg == null || msg.role == null || msg.content == null) continue;
            String role = msg.role.trim().toLowerCase();
            String content = msg.content.trim();
            if (role.isEmpty() || content.isEmpty()) continue;
            if (!isSupportedRole(role)) {
                throw new IllegalArgumentException("unsupported message role: " + msg.role);
            }
            result.add(new LlamaCppChatMessage(role, content));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("no valid messages after conversion");
        }
        return result;
    }

    private boolean isSupportedRole(String role) {
        return "system".equals(role) || "user".equals(role) || "assistant".equals(role);
    }

    private List<String> filterSearchableTexts(List<String> texts) {
        List<String> searchableTexts = new ArrayList<>();
        for (String text : texts) {
            if (text != null && !text.isBlank()) {
                if (text.length() > MAX_EMBEDDING_TEXT_LENGTH) {
                    throw new IllegalArgumentException("search text exceeds maximum length of " + MAX_EMBEDDING_TEXT_LENGTH + " characters");
                }
                searchableTexts.add(text);
            }
        }
        if (searchableTexts.size() > MAX_EMBEDDING_BATCH_SIZE) {
            throw new IllegalArgumentException("search text batch size exceeds maximum of " + MAX_EMBEDDING_BATCH_SIZE);
        }
        return searchableTexts;
    }

    private void validateMaxTokens(int maxTokens) {
        if (maxTokens < 0) {
            throw new IllegalArgumentException("maxTokens cannot be negative");
        }
    }
}
