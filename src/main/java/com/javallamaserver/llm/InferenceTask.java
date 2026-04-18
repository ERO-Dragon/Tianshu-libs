package com.javallamaserver.llm;

import org.argeo.jjml.llm.LlamaCppChatMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class InferenceTask {

    public enum TaskType {
        STREAM_CHAT,
        SYNC_COMPRESS,
        SYNC_TOOL_CALL
    }

    private final String taskId;
    private final TaskType taskType;
    private final List<LlamaCppChatMessage> messages;
    private final SamplerConfig samplerConfig;
    private final Consumer<String> streamCallback;
    private final CompletableFuture<String> syncFuture;
    private volatile boolean cancelled = false;

    public InferenceTask(TaskType taskType,
                         List<LlamaCppChatMessage> messages,
                         SamplerConfig samplerConfig,
                         Consumer<String> streamCallback) {
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.taskType = taskType;
        this.messages = messages;
        this.samplerConfig = samplerConfig != null ? samplerConfig : new SamplerConfig();
        this.streamCallback = streamCallback;
        this.syncFuture = new CompletableFuture<>();
    }

    public static InferenceTask streamChat(List<LlamaCppChatMessage> messages,
                                           SamplerConfig samplerConfig,
                                           Consumer<String> streamCallback) {
        return new InferenceTask(TaskType.STREAM_CHAT, messages, samplerConfig, streamCallback);
    }

    public static InferenceTask syncCompress(List<LlamaCppChatMessage> messages,
                                             SamplerConfig samplerConfig) {
        return new InferenceTask(TaskType.SYNC_COMPRESS, messages, samplerConfig, null);
    }

    public static InferenceTask syncToolCall(List<LlamaCppChatMessage> messages,
                                             SamplerConfig samplerConfig) {
        return new InferenceTask(TaskType.SYNC_TOOL_CALL, messages, samplerConfig, null);
    }

    public String getTaskId() { return taskId; }
    public TaskType getTaskType() { return taskType; }
    public List<LlamaCppChatMessage> getMessages() { return messages; }
    public SamplerConfig getSamplerConfig() { return samplerConfig; }
    public Consumer<String> getStreamCallback() { return streamCallback; }
    public CompletableFuture<String> getSyncFuture() { return syncFuture; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
}
