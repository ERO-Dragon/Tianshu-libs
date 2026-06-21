package com.rheinmetal.tianshu.libs.llm;

import java.util.concurrent.atomic.AtomicReference;

final class MtpAutoTuner {
    static final int DEFAULT_DRAFT_MAX = 3;

    private final boolean supported;
    private final int mtpLayerCount;
    private final AtomicReference<MtpTrialResult> bestTrial = new AtomicReference<>();

    MtpAutoTuner(boolean supported, int mtpLayerCount) {
        this.supported = supported;
        this.mtpLayerCount = mtpLayerCount;
    }

    boolean isSupported() {
        return supported;
    }

    int getMtpLayerCount() {
        return mtpLayerCount;
    }

    int recommendedDraftMax() {
        MtpTrialResult best = bestTrial.get();
        return best == null ? DEFAULT_DRAFT_MAX : best.getDraftMax();
    }

    MtpCapability capability() {
        return new MtpCapability(supported, mtpLayerCount, bestTrial.get());
    }

    void record(MtpTrialResult trial) {
        if (trial == null || !trial.isSuccess()) return;
        bestTrial.updateAndGet(current -> {
            if (current == null) return trial;
            return trial.getTokensPerSecond() > current.getTokensPerSecond() ? trial : current;
        });
    }

    void reset() {
        bestTrial.set(null);
    }
}
