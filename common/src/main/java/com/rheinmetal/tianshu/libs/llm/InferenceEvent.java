package com.rheinmetal.tianshu.libs.llm;

public class InferenceEvent {
    private final String taskId;
    private final InferenceTask.TaskType taskType;
    private final InferenceLane lane;
    private final int priority;
    private final InferenceEventType type;
    private final String message;
    private final int replayCharacters;
    private final int generatedTokens;
    private final Throwable error;

    public InferenceEvent(String taskId,
                          InferenceTask.TaskType taskType,
                          InferenceLane lane,
                          int priority,
                          InferenceEventType type,
                          String message,
                          int replayCharacters,
                          int generatedTokens,
                          Throwable error) {
        if (type == null) throw new IllegalArgumentException("type is required");
        this.taskId = taskId;
        this.taskType = taskType;
        this.lane = lane;
        this.priority = priority;
        this.type = type;
        this.message = message;
        this.replayCharacters = replayCharacters;
        this.generatedTokens = generatedTokens;
        this.error = error;
    }

    public String getTaskId() { return taskId; }
    public InferenceTask.TaskType getTaskType() { return taskType; }
    public InferenceLane getLane() { return lane; }
    public int getPriority() { return priority; }
    public InferenceEventType getType() { return type; }
    public String getMessage() { return message; }
    public int getReplayCharacters() { return replayCharacters; }
    public int getGeneratedTokens() { return generatedTokens; }
    public Throwable getError() { return error; }
    public boolean isChat() { return lane == InferenceLane.CHAT; }
    public boolean isTask() { return lane == InferenceLane.TASK; }
}
