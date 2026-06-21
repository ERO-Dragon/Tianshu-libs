package com.rheinmetal.tianshu.libs.llm;

public final class MtpCapability {
    private final boolean supported;
    private final int mtpLayerCount;
    private final MtpTrialResult bestTrial;

    public MtpCapability(boolean supported, int mtpLayerCount, MtpTrialResult bestTrial) {
        this.supported = supported;
        this.mtpLayerCount = mtpLayerCount;
        this.bestTrial = bestTrial;
    }

    public boolean isSupported() { return supported; }
    public int getMtpLayerCount() { return mtpLayerCount; }
    public boolean isCalibrated() { return bestTrial != null; }
    public MtpTrialResult getBestTrial() { return bestTrial; }
    public int getRecommendedDraftMax() { return bestTrial == null ? 3 : bestTrial.getDraftMax(); }
}
