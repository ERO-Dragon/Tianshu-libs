package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.ggml.GgmlVulkanScheduler;

final class VulkanInferencePriorityScope implements AutoCloseable {
    private static final VulkanInferencePriorityScope NOOP = new VulkanInferencePriorityScope(false);

    private final boolean applied;

    private VulkanInferencePriorityScope(boolean applied) {
        this.applied = applied;
    }

    static VulkanInferencePriorityScope apply(Float priority) {
        if (priority == null) return NOOP;
        try {
            if (!GgmlVulkanScheduler.isSupported()) return NOOP;
            return new VulkanInferencePriorityScope(GgmlVulkanScheduler.setInferencePriority(priority));
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            System.err.println("[VulkanScheduler] Failed to set inference priority, continuing without slicing: "
                    + e.getMessage());
            return NOOP;
        }
    }

    @Override
    public void close() {
        if (!applied) return;
        try {
            GgmlVulkanScheduler.setInferencePriority(1.0f);
        } catch (RuntimeException | UnsatisfiedLinkError ignored) {
        }
    }
}
