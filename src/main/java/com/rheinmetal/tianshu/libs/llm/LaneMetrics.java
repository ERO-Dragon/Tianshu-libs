package com.rheinmetal.tianshu.libs.llm;

public class LaneMetrics {
    private final int chatQueueSize;
    private final int chatMaxQueueSize;
    private final int taskQueueSize;
    private final int taskMaxQueueSize;
    private final String currentLane;
    private final boolean taskSuspended;

    public LaneMetrics(int chatQueueSize,
                       int chatMaxQueueSize,
                       int taskQueueSize,
                       int taskMaxQueueSize,
                       InferenceLane currentLane,
                       boolean taskSuspended) {
        this.chatQueueSize = chatQueueSize;
        this.chatMaxQueueSize = chatMaxQueueSize;
        this.taskQueueSize = taskQueueSize;
        this.taskMaxQueueSize = taskMaxQueueSize;
        this.currentLane = currentLane == null ? null : currentLane.wireName();
        this.taskSuspended = taskSuspended;
    }

    public int getChatQueueSize() { return chatQueueSize; }
    public int getChatMaxQueueSize() { return chatMaxQueueSize; }
    public int getTaskQueueSize() { return taskQueueSize; }
    public int getTaskMaxQueueSize() { return taskMaxQueueSize; }
    public String getCurrentLane() { return currentLane; }
    public boolean isTaskSuspended() { return taskSuspended; }
}
