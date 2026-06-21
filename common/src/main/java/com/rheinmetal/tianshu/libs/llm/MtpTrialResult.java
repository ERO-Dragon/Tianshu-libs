package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.SpeculativeStats;

public final class MtpTrialResult {
    private final int draftMax;
    private final boolean success;
    private final String errorMessage;
    private final long promptTokens;
    private final long generatedTokens;
    private final long draftedTokens;
    private final long acceptedDraftTokens;
    private final long decodeNanos;
    private final double acceptanceRate;
    private final double tokensPerSecond;

    private MtpTrialResult(int draftMax,
                           boolean success,
                           String errorMessage,
                           long promptTokens,
                           long generatedTokens,
                           long draftedTokens,
                           long acceptedDraftTokens,
                           long decodeNanos,
                           double acceptanceRate,
                           double tokensPerSecond) {
        this.draftMax = draftMax;
        this.success = success;
        this.errorMessage = errorMessage;
        this.promptTokens = promptTokens;
        this.generatedTokens = generatedTokens;
        this.draftedTokens = draftedTokens;
        this.acceptedDraftTokens = acceptedDraftTokens;
        this.decodeNanos = decodeNanos;
        this.acceptanceRate = acceptanceRate;
        this.tokensPerSecond = tokensPerSecond;
    }

    public static MtpTrialResult success(int draftMax, SpeculativeStats stats) {
        if (stats == null) throw new IllegalArgumentException("stats is required");
        return new MtpTrialResult(
                draftMax,
                true,
                null,
                stats.promptTokens(),
                stats.generatedTokens(),
                stats.draftedTokens(),
                stats.acceptedDraftTokens(),
                stats.decodeNanos(),
                stats.acceptanceRate(),
                stats.tokensPerSecond()
        );
    }

    public static MtpTrialResult failed(int draftMax, String errorMessage) {
        return new MtpTrialResult(
                draftMax,
                false,
                errorMessage == null || errorMessage.isBlank() ? "MTP trial failed" : errorMessage,
                0,
                0,
                0,
                0,
                0,
                0.0,
                0.0
        );
    }

    public int getDraftMax() { return draftMax; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public long getPromptTokens() { return promptTokens; }
    public long getGeneratedTokens() { return generatedTokens; }
    public long getDraftedTokens() { return draftedTokens; }
    public long getAcceptedDraftTokens() { return acceptedDraftTokens; }
    public long getDecodeNanos() { return decodeNanos; }
    public double getAcceptanceRate() { return acceptanceRate; }
    public double getTokensPerSecond() { return tokensPerSecond; }
}
