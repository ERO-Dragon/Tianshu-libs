package com.rheinmetal.tianshu.libs.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceTask;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.argeo.jjml.llm.LlamaCppChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public class ChatController {

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final LlamaEngine chatEngine;
    private final EmbeddingEngine embeddingEngine;
    private final int requestTimeoutSeconds;

    public ChatController(LlamaEngine chatEngine) {
        this(chatEngine, null, 300);
    }

    public ChatController(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine) {
        this(chatEngine, embeddingEngine, 300);
    }

    public ChatController(LlamaEngine chatEngine, EmbeddingEngine embeddingEngine, int requestTimeoutSeconds) {
        this.chatEngine = chatEngine;
        this.embeddingEngine = embeddingEngine;
        this.requestTimeoutSeconds = Math.max(1, requestTimeoutSeconds);
    }

    public boolean hasQueueCapacity() {
        return chatEngine.hasQueueCapacity();
    }

    public boolean hasQueueCapacity(InferenceLane lane) {
        return chatEngine.hasQueueCapacity(lane);
    }

    public Handler syncHandler() {
        return ctx -> {
            ChatRequest request = parseRequestFromContext(ctx);
            if (request == null) return;
            handleSyncChat(ctx, request);
        };
    }

    public ChatRequest parseRequestFromContext(Context ctx) {
        try {
            ChatRequest request = gson.fromJson(ctx.body(), ChatRequest.class);
            if (request.messages == null || request.messages.isEmpty()) {
                ctx.status(400);
                ctx.result("{\"error\": \"messages is required\"}");
                return null;
            }
            try {
                request.resolveLane();
            } catch (IllegalArgumentException e) {
                ctx.status(400);
                ctx.result(gson.toJson(new ErrorResponse(e.getMessage())));
                return null;
            }
            return request;
        } catch (Exception e) {
            ctx.status(400);
            ctx.result("{\"error\": \"Invalid JSON: " + e.getMessage() + "\"}");
            return null;
        }
    }

    public ChatRequest parseRequestFromString(String jsonBody) {
        try {
            ChatRequest request = gson.fromJson(jsonBody, ChatRequest.class);
            if (request.messages == null || request.messages.isEmpty()) {
                System.err.println("[ChatController] messages is required");
                return null;
            }
            return request;
        } catch (Exception e) {
            System.err.println("[ChatController] Invalid JSON: " + e.getMessage());
            return null;
        }
    }

    public InferenceTask handleStreamChatRaw(ChatRequest request, Consumer<String> dataSender, Runnable doneSender) {
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        long created = System.currentTimeMillis() / 1000;
        String model = chatEngine.getModelAlias();

        InferenceLane lane = request.resolveLane();
        List<LlamaCppChatMessage> messages = convertMessages(request.messages, request.thinking);
        SamplerConfig samplerConfig = buildSamplerConfig(request);

        int maxTokens = normalizeMaxTokens(request.max_tokens);
        int taskPriority = normalizeTaskPriority(request.task_priority);
        boolean taskPreemptible = Boolean.TRUE.equals(request.task_preemptible);
        ReasoningCleaner cleaner = new ReasoningCleaner();
        InferenceTask task = InferenceTask.stream(lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible, token -> {
            String cleanedToken = cleaner.feed(token);
            if (cleanedToken.isEmpty()) return;
            String chunkJson = gson.toJson(new StreamChunk(
                    completionId, created, model,
                    new StreamChunk.StreamChoice(0, new StreamChunk.Delta(cleanedToken), null)
            ));
            dataSender.accept(chunkJson);
        });

        try {
            chatEngine.submitTask(task);
        } catch (RejectedExecutionException e) {
            dataSender.accept(gson.toJson(new ErrorResponse("inference queue is full")));
            doneSender.run();
            return task;
        }

        task.getSyncFuture().whenComplete((fullText, throwable) -> {
            // 【修复点】：如果是被取消的，或者抛异常了，直接走 doneSender 结束，绝对不发数据
            if (task.isCancelled() || throwable != null) {
                doneSender.run();
                return;
            }
            // 只有正常走完的，才发收尾包
            try {
                String stopChunkJson = gson.toJson(new StreamChunk(completionId, created, model, new StreamChunk.StreamChoice(0, new StreamChunk.Delta(null), "stop")));
                dataSender.accept(stopChunkJson);
                String usageChunkJson = gson.toJson(new UsageChunk(completionId, created, model, new UsageInfo(0, 0, 0)));
                dataSender.accept(usageChunkJson);
            } catch (Exception ignored) {}
            doneSender.run();
        });
        return task;
    }

    public void handleSyncChat(Context ctx, ChatRequest request) {
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        long created = System.currentTimeMillis() / 1000;
        String model = chatEngine.getModelAlias();

        List<LlamaCppChatMessage> messages = convertMessages(request.messages, request.thinking);
        SamplerConfig samplerConfig = buildSamplerConfig(request);

        int maxTokens = normalizeMaxTokens(request.max_tokens);
        int taskPriority = normalizeTaskPriority(request.task_priority);
        boolean taskPreemptible = Boolean.TRUE.equals(request.task_preemptible);
        InferenceLane lane = request.resolveLane();
        if (!chatEngine.hasQueueCapacity(lane)) {
            ctx.status(429);
            ctx.result(gson.toJson(new ErrorResponse(lane.wireName() + " inference queue is full")));
            return;
        }
        InferenceTask task = InferenceTask.syncChat(lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible);
        try {
            chatEngine.submitTask(task);
        } catch (RejectedExecutionException e) {
            ctx.status(429);
            ctx.result(gson.toJson(new ErrorResponse(e.getMessage())));
            return;
        }

        try {
            String fullText = ReasoningCleaner.clean(task.getSyncFuture().get(requestTimeoutSeconds, TimeUnit.SECONDS)).trim();
            ChatResponse response = new ChatResponse(
                    completionId, created, model,
                    List.of(new ChatResponse.ChatChoice(
                            0,
                            new ChatResponse.ChatMessage("assistant", fullText),
                            "stop"
                    )),
                    new UsageInfo(0, 0, 0)
            );
            ctx.contentType("application/json");
            ctx.result(gson.toJson(response));
        } catch (TimeoutException e) {
            task.cancel();
            ctx.status(504);
            ctx.result(gson.toJson(new ErrorResponse("request timed out")));
        } catch (RejectedExecutionException e) {
            ctx.status(429);
            ctx.result(gson.toJson(new ErrorResponse("inference queue is full")));
        } catch (Exception e) {
            ctx.status(500);
            ctx.result(gson.toJson(new ErrorResponse(e.getMessage())));
        }
    }

    public String executeSyncChat(ChatRequest request, SamplerConfig samplerOverride) throws Exception {
        validateProgrammaticRequest(request);
        List<LlamaCppChatMessage> messages = convertMessages(request.messages, request.thinking);
        SamplerConfig samplerConfig = samplerOverride != null ? samplerOverride : buildSamplerConfig(request);
        int maxTokens = normalizeMaxTokens(request.max_tokens);
        int taskPriority = normalizeTaskPriority(request.task_priority);
        boolean taskPreemptible = Boolean.TRUE.equals(request.task_preemptible);
        InferenceLane lane = request.resolveLane();
        if (!chatEngine.hasQueueCapacity(lane)) {
            throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
        }
        InferenceTask task = InferenceTask.syncChat(lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible);
        chatEngine.submitTask(task);
        return ReasoningCleaner.clean(task.getSyncFuture().get(requestTimeoutSeconds, TimeUnit.SECONDS)).trim();
    }

    public CompletableFuture<String> executeStreamChat(ChatRequest request,
                                                       SamplerConfig samplerOverride,
                                                       Consumer<String> tokenConsumer) {
        try {
            validateProgrammaticRequest(request);
            List<LlamaCppChatMessage> messages = convertMessages(request.messages, request.thinking);
            SamplerConfig samplerConfig = samplerOverride != null ? samplerOverride : buildSamplerConfig(request);
            int maxTokens = normalizeMaxTokens(request.max_tokens);
            int taskPriority = normalizeTaskPriority(request.task_priority);
            boolean taskPreemptible = Boolean.TRUE.equals(request.task_preemptible);
            InferenceLane lane = request.resolveLane();
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }

            ReasoningCleaner cleaner = new ReasoningCleaner();
            InferenceTask task = InferenceTask.stream(lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible, token -> {
                String cleaned = cleaner.feed(token);
                if (!cleaned.isEmpty() && tokenConsumer != null) {
                    tokenConsumer.accept(cleaned);
                }
            });
            chatEngine.submitTask(task);
            return task.getSyncFuture().thenApply(text -> ReasoningCleaner.clean(text).trim());
        } catch (Exception e) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    public CompletableFuture<String> executeAsyncChat(ChatRequest request, SamplerConfig samplerOverride) {
        try {
            validateProgrammaticRequest(request);
            List<LlamaCppChatMessage> messages = convertMessages(request.messages, request.thinking);
            SamplerConfig samplerConfig = samplerOverride != null ? samplerOverride : buildSamplerConfig(request);
            int maxTokens = normalizeMaxTokens(request.max_tokens);
            int taskPriority = normalizeTaskPriority(request.task_priority);
            boolean taskPreemptible = Boolean.TRUE.equals(request.task_preemptible);
            InferenceLane lane = request.resolveLane();
            if (!chatEngine.hasQueueCapacity(lane)) {
                throw new RejectedExecutionException(lane.wireName() + " inference queue is full");
            }
            InferenceTask task = InferenceTask.syncChat(lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible);
            chatEngine.submitTask(task);
            return task.getSyncFuture().thenApply(text -> ReasoningCleaner.clean(text).trim());
        } catch (Exception e) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    private SamplerConfig buildSamplerConfig(ChatRequest request) {
        SamplerConfig config = new SamplerConfig();
        String profile = chatEngine.getModelProfile();
        boolean thinking = Boolean.TRUE.equals(request.thinking);

        if ("qwen3.5".equalsIgnoreCase(profile) && thinking) {
            config.setTemperature(1.0f);
            config.setTopP(0.95f);
            config.setTopK(20);
            config.setEnableThinking(true);
        } else if (request.temperature != null) {
            config.setTemperature(request.temperature);
        }

        return config;
    }

    private void validateProgrammaticRequest(ChatRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.messages == null || request.messages.isEmpty()) {
            throw new IllegalArgumentException("messages is required");
        }
        request.resolveLane();
    }

    private List<LlamaCppChatMessage> convertMessages(List<ChatMessage> chatMessages, Boolean thinking) {
        List<LlamaCppChatMessage> result = new ArrayList<>();
        String modelProfile = chatEngine.getModelProfile();
        boolean thinkingApplied = thinking == null;
        for (ChatMessage msg : chatMessages) {
            if (msg == null || msg.role == null || msg.content == null) continue;
            String content = msg.content;
            if (!thinkingApplied && "user".equalsIgnoreCase(msg.role)) {
                content = adaptThinkingForProfile(content, thinking, modelProfile);
                thinkingApplied = true;
            }
            result.add(new LlamaCppChatMessage(msg.role, content));
        }
        return result;
    }

    private String adaptThinkingForProfile(String content, boolean thinking, String modelProfile) {
        if ("qwen3".equalsIgnoreCase(modelProfile)) {
            String prefix = thinking ? "/think" : "/no_think";
            if (content.startsWith("/think") || content.startsWith("/no_think")) return content;
            return prefix + "\n" + content;
        }
        return content;
    }

    private int normalizeMaxTokens(Integer maxTokens) {
        if (maxTokens == null || maxTokens <= 0) return 0;
        return maxTokens;
    }

    private int normalizeTaskPriority(Integer taskPriority) {
        if (taskPriority == null) return 0;
        return Math.max(-1000, Math.min(1000, taskPriority));
    }

    private static class ReasoningCleaner {
        private static final String[][] TAG_PAIRS = {
                {"<think>", "</think>"},
                {"<reasoning>", "</reasoning>"},
                {"<analysis>", "</analysis>"},
                {"<thought>", "</thought>"},
                {"[think]", "[/think]"},
                {"[reasoning]", "[/reasoning]"},
                {"[analysis]", "[/analysis]"},
                {"[thought]", "[/thought]"}
        };

        private final StringBuilder pending = new StringBuilder();
        private String activeCloseTag = null;

        String feed(String token) {
            if (token == null || token.isEmpty()) return "";
            pending.append(token);
            StringBuilder output = new StringBuilder();
            while (pending.length() > 0) {
                String lower = pending.toString().toLowerCase();
                if (activeCloseTag != null) {
                    int close = lower.indexOf(activeCloseTag);
                    if (close < 0) {
                        int keep = longestTagPrefix(pending);
                        pending.delete(0, pending.length() - keep);
                        break;
                    }
                    pending.delete(0, close + activeCloseTag.length());
                    activeCloseTag = null;
                    continue;
                }

                TagMatch next = findNextTag(lower);
                if (next == null) {
                    int keep = longestTagPrefix(pending);
                    int emitLength = pending.length() - keep;
                    if (emitLength > 0) {
                        output.append(pending, 0, emitLength);
                        pending.delete(0, emitLength);
                    }
                    break;
                }

                if (next.opening) {
                    output.append(pending, 0, next.index);
                    pending.delete(0, next.index + next.tag.length());
                    activeCloseTag = next.closeTag;
                } else {
                    output.append(pending, 0, next.index);
                    pending.delete(0, next.index + next.tag.length());
                }
            }
            return output.toString();
        }

        static String clean(String text) {
            if (text == null || text.isEmpty()) return "";
            String result = text;
            for (String[] pair : TAG_PAIRS) {
                result = result.replaceAll("(?is)" + regexQuote(pair[0]) + ".*?" + regexQuote(pair[1]), "");
                result = result.replaceAll("(?i)" + regexQuote(pair[0]), "");
                result = result.replaceAll("(?i)" + regexQuote(pair[1]), "");
            }
            return result;
        }

        private static TagMatch findNextTag(String lower) {
            TagMatch best = null;
            for (String[] pair : TAG_PAIRS) {
                int open = lower.indexOf(pair[0]);
                if (open >= 0 && (best == null || open < best.index)) {
                    best = new TagMatch(open, pair[0], pair[1], true);
                }
                int close = lower.indexOf(pair[1]);
                if (close >= 0 && (best == null || close < best.index)) {
                    best = new TagMatch(close, pair[1], null, false);
                }
            }
            return best;
        }

        private static int longestTagPrefix(StringBuilder value) {
            String lower = value.toString().toLowerCase();
            int maxTagLength = 0;
            for (String[] pair : TAG_PAIRS) {
                maxTagLength = Math.max(maxTagLength, pair[0].length());
                maxTagLength = Math.max(maxTagLength, pair[1].length());
            }
            int max = Math.min(lower.length(), maxTagLength - 1);
            int best = 0;
            for (int i = 1; i <= max; i++) {
                String suffix = lower.substring(lower.length() - i);
                for (String[] pair : TAG_PAIRS) {
                    if (pair[0].startsWith(suffix) || pair[1].startsWith(suffix)) {
                        best = i;
                        break;
                    }
                }
            }
            return best;
        }

        private static String regexQuote(String value) {
            return java.util.regex.Pattern.quote(value);
        }

        private static class TagMatch {
            private final int index;
            private final String tag;
            private final String closeTag;
            private final boolean opening;

            private TagMatch(int index, String tag, String closeTag, boolean opening) {
                this.index = index;
                this.tag = tag;
                this.closeTag = closeTag;
                this.opening = opening;
            }
        }
    }

    public static class ErrorResponse {
        public String error;

        public ErrorResponse(String error) {
            this.error = error == null ? "unknown error" : error;
        }
    }

    public static class ChatRequest {
        public List<ChatMessage> messages;
        public Float temperature;
        public Boolean stream;
        public Integer max_tokens;
        public Boolean thinking;
        public Integer task_priority;
        public Boolean task_preemptible;
        public String lane;

        public InferenceLane resolveLane() {
            return InferenceLane.parse(lane);
        }
    }

    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage() {
        }

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class StreamChunk {
        public String id;
        public String object = "chat.completion.chunk";
        public long created;
        public String model;
        public List<StreamChoice> choices;

        public StreamChunk(String id, long created, String model, StreamChoice choice) {
            this.id = id;
            this.created = created;
            this.model = model;
            this.choices = List.of(choice);
        }

        public static class StreamChoice {
            public int index;
            public Delta delta;
            @SerializedName("finish_reason")
            public String finishReason;

            public StreamChoice(int index, Delta delta, String finishReason) {
                this.index = index;
                this.delta = delta;
                this.finishReason = finishReason;
            }
        }

        public static class Delta {
            public String content;

            public Delta(String content) {
                if (content != null) {
                    this.content = content;
                }
            }
        }
    }

    public static class UsageChunk {
        public String id;
        public String object = "chat.completion.chunk";
        public long created;
        public String model;
        public List<Object> choices = List.of();
        public UsageInfo usage;

        public UsageChunk(String id, long created, String model, UsageInfo usage) {
            this.id = id;
            this.created = created;
            this.model = model;
            this.usage = usage;
        }
    }

    public static class ChatResponse {
        public String id;
        public String object = "chat.completion";
        public long created;
        public String model;
        public List<ChatChoice> choices;
        public UsageInfo usage;

        public ChatResponse(String id, long created, String model,
                           List<ChatChoice> choices, UsageInfo usage) {
            this.id = id;
            this.created = created;
            this.model = model;
            this.choices = choices;
            this.usage = usage;
        }

        public static class ChatChoice {
            public int index;
            public ChatMessage message;
            @SerializedName("finish_reason")
            public String finishReason;

            public ChatChoice(int index, ChatMessage message, String finishReason) {
                this.index = index;
                this.message = message;
                this.finishReason = finishReason;
            }
        }

        public static class ChatMessage {
            public String role;
            public String content;

            public ChatMessage(String role, String content) {
                this.role = role;
                this.content = content;
            }
        }
    }

    public static class UsageInfo {
        @SerializedName("prompt_tokens")
        public int promptTokens;
        @SerializedName("completion_tokens")
        public int completionTokens;
        @SerializedName("total_tokens")
        public int totalTokens;

        public UsageInfo(int promptTokens, int completionTokens, int totalTokens) {
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.totalTokens = totalTokens;
        }
    }
}
