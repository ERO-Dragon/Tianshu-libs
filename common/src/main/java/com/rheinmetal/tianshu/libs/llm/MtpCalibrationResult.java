package com.rheinmetal.tianshu.libs.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MtpCalibrationResult {
    private final boolean supported;
    private final int mtpLayerCount;
    private final int maxDraftMaxTested;
    private final MtpTrialResult bestTrial;
    private final List<MtpTrialResult> trials;
    private final String message;

    private MtpCalibrationResult(boolean supported,
                                 int mtpLayerCount,
                                 int maxDraftMaxTested,
                                 MtpTrialResult bestTrial,
                                 List<MtpTrialResult> trials,
                                 String message) {
        this.supported = supported;
        this.mtpLayerCount = mtpLayerCount;
        this.maxDraftMaxTested = maxDraftMaxTested;
        this.bestTrial = bestTrial;
        this.trials = Collections.unmodifiableList(new ArrayList<>(trials == null ? List.of() : trials));
        this.message = message;
    }

    public static MtpCalibrationResult unsupported(int mtpLayerCount) {
        return new MtpCalibrationResult(false, mtpLayerCount, 0, null, List.of(), "Model does not support MTP.");
    }

    public static MtpCalibrationResult completed(int mtpLayerCount,
                                                 int maxDraftMaxTested,
                                                 List<MtpTrialResult> trials) {
        MtpTrialResult best = null;
        if (trials != null) {
            for (MtpTrialResult trial : trials) {
                if (trial == null || !trial.isSuccess()) continue;
                if (best == null || trial.getTokensPerSecond() > best.getTokensPerSecond()) {
                    best = trial;
                }
            }
        }
        String message = best == null ? "No successful MTP trial." : "MTP calibration completed.";
        return new MtpCalibrationResult(true, mtpLayerCount, maxDraftMaxTested, best, trials, message);
    }

    public static MtpCalibrationResult failed(int mtpLayerCount,
                                              int maxDraftMaxTested,
                                              List<MtpTrialResult> trials,
                                              String message) {
        String effectiveMessage = message == null || message.isBlank()
                ? "MTP calibration failed."
                : message;
        return new MtpCalibrationResult(true, mtpLayerCount, maxDraftMaxTested, null, trials, effectiveMessage);
    }

    public boolean isSupported() { return supported; }
    public int getMtpLayerCount() { return mtpLayerCount; }
    public int getMaxDraftMaxTested() { return maxDraftMaxTested; }
    public MtpTrialResult getBestTrial() { return bestTrial; }
    public List<MtpTrialResult> getTrials() { return trials; }
    public String getMessage() { return message; }
    public boolean hasBestTrial() { return bestTrial != null; }
    public int getBestDraftMax() { return bestTrial == null ? 0 : bestTrial.getDraftMax(); }
}
