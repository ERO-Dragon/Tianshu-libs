package com.rheinmetal.tianshu.libs.llm;

public class LaneConfig {
    private final InferenceLane lane;
    private final int contextSize;
    private final int threadCount;
    private final int maxQueueSize;

    public LaneConfig(InferenceLane lane, int contextSize, int threadCount, int maxQueueSize) {
        if (lane == null) throw new IllegalArgumentException("lane is required");
        if (contextSize < 1) throw new IllegalArgumentException("contextSize must be positive");
        if (threadCount < 1) throw new IllegalArgumentException("threadCount must be positive");
        if (lane == InferenceLane.TASK) {
            if (maxQueueSize < 0) throw new IllegalArgumentException("task maxQueueSize cannot be negative");
        } else if (maxQueueSize < 1) {
            throw new IllegalArgumentException("maxQueueSize must be positive");
        }
        this.lane = lane;
        this.contextSize = contextSize;
        this.threadCount = threadCount;
        this.maxQueueSize = maxQueueSize;
    }

    public InferenceLane getLane() { return lane; }
    public int getContextSize() { return contextSize; }
    public int getThreadCount() { return threadCount; }
    public int getMaxQueueSize() { return maxQueueSize; }
}
