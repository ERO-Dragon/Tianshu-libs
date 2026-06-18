package com.rheinmetal.tianshu.libs.llm;

public class LaneMetrics {
    private final int chatQueueSize;
    private final int chatMaxQueueSize;
    private final int taskLoad;
    private final int taskHotSuspendSlots;
    private final String currentLane;
    private final boolean taskSuspended;

    public LaneMetrics(int chatQueueSize,
                       int chatMaxQueueSize,
                       int taskLoad,
                       int taskHotSuspendSlots,
                       InferenceLane currentLane,
                       boolean taskSuspended) {
        this.chatQueueSize = chatQueueSize;
        this.chatMaxQueueSize = chatMaxQueueSize;
        this.taskLoad = taskLoad;
        this.taskHotSuspendSlots = taskHotSuspendSlots;
        this.currentLane = currentLane == null ? null : currentLane.wireName();
        this.taskSuspended = taskSuspended;
    }

    public int getChatQueueSize() { return chatQueueSize; }
    public int getChatMaxQueueSize() { return chatMaxQueueSize; }
    public int getTaskLoad() { return taskLoad; }
    public int getTaskQueueSize() { return taskLoad; }
    public int getTaskHotSuspendSlots() { return taskHotSuspendSlots; }
    /**
     * @deprecated TASK queue is unbounded; this value is the internal hot-suspend slot count and is always 0.
     */
    @Deprecated
    public int getTaskMaxQueueSize() { return taskHotSuspendSlots; }
    public String getCurrentLane() { return currentLane; }
    public boolean isTaskSuspended() { return taskSuspended; }
}
