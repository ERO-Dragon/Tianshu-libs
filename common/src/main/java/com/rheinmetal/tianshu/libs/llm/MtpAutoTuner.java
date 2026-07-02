package com.rheinmetal.tianshu.libs.llm;

import java.util.concurrent.atomic.AtomicReference;

final class MtpAutoTuner {
    static final int DEFAULT_DRAFT_MAX = 1;

    private final boolean supported;
    private final int mtpLayerCount;
    private final int initialDraftMax;
    private final AtomicReference<MtpTrialResult> bestTrial = new AtomicReference<>();

    MtpAutoTuner(boolean supported, int mtpLayerCount) {
        this(supported, mtpLayerCount, null);
    }

    MtpAutoTuner(boolean supported, int mtpLayerCount, Integer initialDraftMax) {
        this.supported = supported;
        this.mtpLayerCount = mtpLayerCount;
        this.initialDraftMax = initialDraftMax == null ? DEFAULT_DRAFT_MAX : initialDraftMax;
    }

    boolean isSupported() {
        return supported;
    }

    int getMtpLayerCount() {
        return mtpLayerCount;
    }

    int recommendedDraftMax() {
        MtpTrialResult best = bestTrial.get();
        return best == null ? initialDraftMax : best.getDraftMax();
    }

    MtpCapability capability() {
        return new MtpCapability(supported, mtpLayerCount, recommendedDraftMax(), bestTrial.get());
    }

    boolean record(MtpTrialResult trial) {
        if (trial == null || !trial.isSuccess()) return false;
        boolean[] updated = new boolean[1];
        bestTrial.updateAndGet(current -> {
            if (current == null || trial.getTokensPerSecond() > current.getTokensPerSecond()) {
                updated[0] = true;
                return trial;
            }
            return current;
        });
        return updated[0];
    }

    void reset() {
        bestTrial.set(null);
    }
}
