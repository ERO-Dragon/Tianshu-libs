package com.javallamaserver.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.javallamaserver.llm.InferenceTask;
import com.javallamaserver.llm.LlamaEngine;
import com.javallamaserver.llm.SamplerConfig;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import org.argeo.jjml.llm.LlamaCppChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public class ChatController {

    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

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

    public void handleStreamChatRaw(ChatRequest request, Consumer<String> dataSender, Runnable doneSender) {
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        long created = System.currentTimeMillis() / 1000;
        String model = LlamaEngine.getInstance().getModelAlias();

        List<LlamaCppChatMessage> messages = convertMessages(request.messages);

        SamplerConfig samplerConfig = new SamplerConfig();
        if (request.temperature != null) {
            samplerConfig.setTemperature(request.temperature);
        }

        InferenceTask task = InferenceTask.streamChat(messages, samplerConfig, token -> {
            String chunkJson = gson.toJson(new StreamChunk(
                    completionId, created, model,
                    new StreamChunk.StreamChoice(0, new StreamChunk.Delta(token), null)
            ));
            dataSender.accept(chunkJson);
        });

        LlamaEngine.getInstance().submitTask(task);

        task.getSyncFuture().whenComplete((fullText, throwable) -> {
            if (throwable != null) {
                System.err.println("[ChatController] Task failed: " + throwable.getMessage());
            }
            try {
                String stopChunkJson = gson.toJson(new StreamChunk(
                        completionId, created, model,
                        new StreamChunk.StreamChoice(0, new StreamChunk.Delta(null), "stop")
                ));
                dataSender.accept(stopChunkJson);

                String usageChunkJson = gson.toJson(new UsageChunk(
                        completionId, created, model, new UsageInfo(0, 0, 0)
                ));
                dataSender.accept(usageChunkJson);
            } catch (Exception ignored) {
            }
            doneSender.run();
        });
    }

    private void handleSyncChat(Context ctx, ChatRequest request) {
        String completionId = "chatcmpl-" + UUID.randomUUID().toString().substring(0, 8);
        long created = System.currentTimeMillis() / 1000;
        String model = LlamaEngine.getInstance().getModelAlias();

        List<LlamaCppChatMessage> messages = convertMessages(request.messages);

        SamplerConfig samplerConfig = new SamplerConfig();
        if (request.temperature != null) {
            samplerConfig.setTemperature(request.temperature);
        }

        InferenceTask task = InferenceTask.streamChat(messages, samplerConfig, null);
        LlamaEngine.getInstance().submitTask(task);

        try {
            String fullText = task.getSyncFuture().get();
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
        } catch (Exception e) {
            ctx.status(500);
            ctx.result("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }

    private List<LlamaCppChatMessage> convertMessages(List<ChatMessage> chatMessages) {
        List<LlamaCppChatMessage> result = new ArrayList<>();
        for (ChatMessage msg : chatMessages) {
            result.add(new LlamaCppChatMessage(msg.role, msg.content));
        }
        return result;
    }

    public static class ChatRequest {
        public List<ChatMessage> messages;
        public Float temperature;
        public Boolean stream;
        public Integer max_tokens;
    }

    public static class ChatMessage {
        public String role;
        public String content;
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
