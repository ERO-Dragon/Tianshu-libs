package com.javallamaserver.llm;

import java.util.concurrent.atomic.AtomicLong;

public class PrioritizedInferenceTask implements Comparable<PrioritizedInferenceTask> {
    private static final AtomicLong SEQUENCE = new AtomicLong(0);

    private final InferenceTask task;
    private final long sequence;

    public PrioritizedInferenceTask(InferenceTask task) {
        if (task == null) throw new IllegalArgumentException("task is required");
        this.task = task;
        this.sequence = SEQUENCE.getAndIncrement();
    }

    public InferenceTask getTask() {
        return task;
    }

    @Override
    public int compareTo(PrioritizedInferenceTask other) {
        int priorityComparison = Integer.compare(other.task.getTaskPriority(), task.getTaskPriority());
        if (priorityComparison != 0) return priorityComparison;
        return Long.compare(sequence, other.sequence);
    }
}
