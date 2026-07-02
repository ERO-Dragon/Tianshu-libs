package com.rheinmetal.tianshu.libs.llm;

public final class MtpCapability {
    private final boolean supported;
    private final int mtpLayerCount;
    private final int recommendedDraftMax;
    private final MtpTrialResult bestTrial;

    public MtpCapability(boolean supported, int mtpLayerCount, MtpTrialResult bestTrial) {
        this(supported,
                mtpLayerCount,
                bestTrial == null ? MtpAutoTuner.DEFAULT_DRAFT_MAX : bestTrial.getDraftMax(),
                bestTrial);
    }

    MtpCapability(boolean supported, int mtpLayerCount, int recommendedDraftMax, MtpTrialResult bestTrial) {
        this.supported = supported;
        this.mtpLayerCount = mtpLayerCount;
        this.recommendedDraftMax = recommendedDraftMax;
        this.bestTrial = bestTrial;
    }

    public boolean isSupported() { return supported; }
    public int getMtpLayerCount() { return mtpLayerCount; }
    public boolean isCalibrated() { return bestTrial != null; }
    public MtpTrialResult getBestTrial() { return bestTrial; }
    public int getRecommendedDraftMax() { return recommendedDraftMax; }
}
