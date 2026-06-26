package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceOptions;
import com.rheinmetal.tianshu.libs.llm.InferenceTask;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest;
import com.rheinmetal.tianshu.libs.llm.MtpCalibrationResult;
import com.rheinmetal.tianshu.libs.llm.MtpCapability;
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

    public CompletableFuture<String> chat(List<ChatMessage> messages) {
        return chat(messages, null, 0);
    }

    public CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
        return chat(messages, sampler, maxTokens, null);
    }

    public CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options) {
        return doAsyncChat(InferenceLane.CHAT, messages, sampler, maxTokens, 0, false, options);
    }

    public CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
        return chatWithUsage(messages, sampler, maxTokens, null);
    }

    public CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options) {
        return doAsyncChatWithUsage(InferenceLane.CHAT, messages, sampler, maxTokens, 0, false, options);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, Consumer<String> onToken) {
        return chatStream(messages, null, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) {
        return chatStream(messages, sampler, 0, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, Consumer<String> onToken) {
        return chatStream(messages, sampler, maxTokens, null, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                InferenceOptions options,
                                                Consumer<String> onToken) {
        return doStreamChat(InferenceLane.CHAT, messages, sampler, maxTokens, 0, false, options, onToken).future;
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                InferenceOptions options,
                                                Consumer<String> onToken,
                                                Consumer<LlmStreamFinish> onFinish) {
        return doStreamChat(InferenceLane.CHAT, messages, sampler, maxTokens, 0, false, options, onToken, onFinish).future;
    }

    public CompletableFuture<String> task(List<ChatMessage> messages) {
        return task(messages, null, 0, 0, false);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                           SamplerConfig sampler,
                                           int maxTokens,
                                           int priority,
                                           boolean preemptible) {
        return task(messages, sampler, maxTokens, priority, preemptible, null);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                          SamplerConfig sampler,
                                          int maxTokens,
                                          int priority,
                                          boolean preemptible,
                                          InferenceOptions options) {
        return doAsyncChat(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, options);
    }

    public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages,
                                                                SamplerConfig sampler,
                                                                int maxTokens,
                                                                int priority,
                                                                boolean preemptible,
                                                                InferenceOptions options) {
        return doAsyncChatWithUsage(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, options);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                           SamplerConfig sampler,
                                           int maxTokens,
                                           int priority,
                                           boolean preemptible,
                                           Consumer<String> tokenConsumer) {
        return taskStream(messages, sampler, maxTokens, priority, preemptible, null, tokenConsumer);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible,
                                                InferenceOptions options,
                                                Consumer<String> tokenConsumer) {
        return doStreamChat(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer).future;
    }

    public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages,
                                                                      SamplerConfig sampler,
                                                                      int maxTokens,
                                                                      int priority,
                                                                      boolean preemptible,
                                                                      InferenceOptions options,
                                                                      Consumer<String> tokenConsumer,
                                                                      Consumer<LlmStreamFinish> finishConsumer) {
        return doStreamChatWithUsage(InferenceLane.TASK, messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, finishConsumer);
    }

    public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) {
        List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
        return chatEngine.countChatPromptTokens(llamaMessages, sampler);
    }

    public boolean supportsMtp() {
        return chatEngine.supportsMtp();
    }

    public MtpCapability getMtpCapability() {
        return chatEngine.getMtpCapability();
    }

    public CompletableFuture<MtpCalibrationResult> calibrateMtpAsync(MtpCalibrationRequest request) {
        return chatEngine.calibrateMtpAsync(request);
    }

    public MtpCalibrationResult calibrateMtp(MtpCalibrationRequest request) throws Exception {
        CompletableFuture<MtpCalibrationResult> future = calibrateMtpAsync(request);
        try {
            return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(false);
            throw new Exception("MTP calibration timeout after " + requestTimeoutSeconds + " seconds");
        }
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

    private StreamResult doStreamChat(InferenceLane lane,
                                                    List<ChatMessage> messages,
                                                    SamplerConfig sampler,
                                                    int maxTokens,
                                                    int priority,
                                                    boolean preemptible,
                                                    InferenceOptions options,
                                                    Consumer<String> tokenConsumer) {
        return doStreamChat(lane, messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, null);
    }

    private StreamResult doStreamChat(InferenceLane lane,
                                                    List<ChatMessage> messages,
                                                    SamplerConfig sampler,
                                                    int maxTokens,
                                                    int priority,
                                                    boolean preemptible,
                                                    InferenceOptions options,
                                                    Consumer<String> tokenConsumer,
                                                    Consumer<LlmStreamFinish> finishConsumer) {
        try {
            validateMaxTokens(maxTokens);
            if (tokenConsumer == null) {
                throw new IllegalArgumentException("tokenConsumer is required");
            }
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.stream(lane, llamaMessages, sampler, maxTokens, priority, preemptible, tokenConsumer, finishConsumer, options);
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
                                                   boolean preemptible,
                                                   InferenceOptions options) {
        CompletableFuture<LlmGenerationResult> resultFuture = doAsyncChatWithUsage(lane, messages, sampler, maxTokens, priority, preemptible, options);
        CompletableFuture<String> textFuture = new CompletableFuture<>();
        resultFuture.whenComplete((result, error) -> {
            if (error != null) {
                textFuture.completeExceptionally(error);
            } else {
                textFuture.complete(result.text());
            }
        });
        textFuture.whenComplete((result, error) -> {
            if (textFuture.isCancelled()) {
                resultFuture.cancel(false);
            }
        });
        return textFuture;
    }

    private CompletableFuture<LlmGenerationResult> doAsyncChatWithUsage(InferenceLane lane,
                                                                        List<ChatMessage> messages,
                                                                        SamplerConfig sampler,
                                                                        int maxTokens,
                                                                        int priority,
                                                                        boolean preemptible,
                                                                        InferenceOptions options) {
        try {
            validateMaxTokens(maxTokens);
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            List<LlamaCppChatMessage> llamaMessages = convertMessages(messages);
            InferenceTask task = InferenceTask.syncChat(lane, llamaMessages, sampler, maxTokens, priority, preemptible, options);
            chatEngine.submitTask(task);
            return cancellableGenerationFuture(task);
        } catch (Exception e) {
            CompletableFuture<LlmGenerationResult> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private CompletableFuture<LlmGenerationResult> doStreamChatWithUsage(InferenceLane lane,
                                                                         List<ChatMessage> messages,
                                                                         SamplerConfig sampler,
                                                                         int maxTokens,
                                                                         int priority,
                                                                         boolean preemptible,
                                                                         InferenceOptions options,
                                                                         Consumer<String> tokenConsumer,
                                                                         Consumer<LlmStreamFinish> finishConsumer) {
        StreamResult stream = doStreamChat(lane, messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, finishConsumer);
        if (stream.task == null) {
            CompletableFuture<LlmGenerationResult> failed = new CompletableFuture<>();
            stream.future.whenComplete((result, error) -> {
                if (error != null) {
                    failed.completeExceptionally(error);
                } else {
                    failed.completeExceptionally(new IllegalStateException("Stream task was not created"));
                }
            });
            return failed;
        }
        return cancellableGenerationFuture(stream.task);
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

    private CompletableFuture<LlmGenerationResult> cancellableGenerationFuture(InferenceTask task) {
        CompletableFuture<LlmGenerationResult> exposed = new CompletableFuture<>();
        task.getGenerationFuture().whenComplete((result, error) -> {
            if (error != null) {
                exposed.completeExceptionally(error);
            } else {
                exposed.complete(result);
            }
        });
        exposed.whenComplete((result, error) -> {
            if (exposed.isCancelled() && !task.getGenerationFuture().isDone()) {
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
