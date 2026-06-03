package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppChatMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class InferenceTask {

    public enum TaskType {
        STREAM_COMPLETION,
        SYNC_CHAT,
        SYNC_COMPRESS,
        SYNC_TOOL_CALL
    }

    private final String taskId;
    private final TaskType taskType;
    private final InferenceLane lane;
    private final List<LlamaCppChatMessage> messages;
    private final SamplerConfig samplerConfig;
    private final int maxTokens;
    private final int taskPriority;
    private final boolean taskPreemptible;
    private final Consumer<String> streamCallback;
    private final CompletableFuture<String> syncFuture;
    private volatile boolean cancelled = false;
    private final AtomicBoolean taskLaneSlotReleased = new AtomicBoolean(false);

    public InferenceTask(TaskType taskType,
                         InferenceLane lane,
                         List<LlamaCppChatMessage> messages,
                         SamplerConfig samplerConfig,
                         int maxTokens,
                         int taskPriority,
                         boolean taskPreemptible,
                         Consumer<String> streamCallback) {
        if (taskType == null) throw new IllegalArgumentException("taskType is required");
        if (lane == null) throw new IllegalArgumentException("lane is required");
        this.taskId = UUID.randomUUID().toString().substring(0, 8);
        this.taskType = taskType;
        this.lane = lane;
        this.messages = messages;
        this.samplerConfig = samplerConfig != null ? samplerConfig.copy() : new SamplerConfig();
        this.maxTokens = maxTokens;
        this.taskPriority = taskPriority;
        this.taskPreemptible = taskPreemptible;
        this.streamCallback = streamCallback;
        this.syncFuture = new CompletableFuture<>();
    }

    public static InferenceTask stream(InferenceLane lane,
                                       List<LlamaCppChatMessage> messages,
                                       SamplerConfig samplerConfig,
                                       int maxTokens,
                                       Consumer<String> streamCallback) {
        return stream(lane, messages, samplerConfig, maxTokens, 0, false, streamCallback);
    }

    public static InferenceTask stream(InferenceLane lane,
                                       List<LlamaCppChatMessage> messages,
                                       SamplerConfig samplerConfig,
                                       int maxTokens,
                                       int taskPriority,
                                       boolean taskPreemptible,
                                       Consumer<String> streamCallback) {
        return new InferenceTask(TaskType.STREAM_COMPLETION, lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible, streamCallback);
    }

    public static InferenceTask streamChat(List<LlamaCppChatMessage> messages,
                                           SamplerConfig samplerConfig,
                                           int maxTokens,
                                           Consumer<String> streamCallback) {
        return stream(InferenceLane.CHAT, messages, samplerConfig, maxTokens, streamCallback);
    }

    public static InferenceTask syncChat(InferenceLane lane,
                                         List<LlamaCppChatMessage> messages,
                                         SamplerConfig samplerConfig,
                                         int maxTokens) {
        return syncChat(lane, messages, samplerConfig, maxTokens, 0);
    }

    public static InferenceTask syncChat(InferenceLane lane,
                                         List<LlamaCppChatMessage> messages,
                                         SamplerConfig samplerConfig,
                                         int maxTokens,
                                         int taskPriority) {
        return syncChat(lane, messages, samplerConfig, maxTokens, taskPriority, false);
    }

    public static InferenceTask syncChat(InferenceLane lane,
                                         List<LlamaCppChatMessage> messages,
                                         SamplerConfig samplerConfig,
                                         int maxTokens,
                                         int taskPriority,
                                         boolean taskPreemptible) {
        return new InferenceTask(TaskType.SYNC_CHAT, lane, messages, samplerConfig, maxTokens, taskPriority, taskPreemptible, null);
    }

    public static InferenceTask syncCompress(List<LlamaCppChatMessage> messages,
                                             SamplerConfig samplerConfig,
                                             int maxTokens) {
        return new InferenceTask(TaskType.SYNC_COMPRESS, InferenceLane.TASK, messages, samplerConfig, maxTokens, 0, false, null);
    }

    public static InferenceTask syncToolCall(List<LlamaCppChatMessage> messages,
                                             SamplerConfig samplerConfig,
                                             int maxTokens) {
        return new InferenceTask(TaskType.SYNC_TOOL_CALL, InferenceLane.TASK, messages, samplerConfig, maxTokens, 0, false, null);
    }

    public String getTaskId() { return taskId; }
    public TaskType getTaskType() { return taskType; }
    public InferenceLane getLane() { return lane; }
    public List<LlamaCppChatMessage> getMessages() { return messages; }
    public SamplerConfig getSamplerConfig() { return samplerConfig; }
    public int getMaxTokens() { return maxTokens; }
    public int getTaskPriority() { return taskPriority; }
    public boolean isTaskPreemptible() { return taskPreemptible; }
    public Consumer<String> getStreamCallback() { return streamCallback; }
    public CompletableFuture<String> getSyncFuture() { return syncFuture; }
    public boolean isCancelled() { return cancelled; }
    public void cancel() { this.cancelled = true; }
    boolean releaseTaskLaneSlotOnce() { return taskLaneSlotReleased.compareAndSet(false, true); }
}
