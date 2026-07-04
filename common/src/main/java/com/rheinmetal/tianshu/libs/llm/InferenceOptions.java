package com.rheinmetal.tianshu.libs.llm;

/**
 * Request-scoped runtime controls. These options affect how inference is
 * executed, not how tokens are sampled.
 */
public final class InferenceOptions {
    private static final int MAX_MTP_DRAFT_MAX = MtpCalibrationRequest.MAX_CALIBRATION_DRAFT_MAX;
    private static final InferenceOptions DEFAULTS = new Builder().build();

    private final boolean mtpEnabled;
    private final Integer mtpDraftMax;
    private final Float vulkanPriority;
    private final boolean captureThinkingContent;
    private final String toolsJson;

    private InferenceOptions(Builder builder) {
        this.mtpEnabled = builder.mtpEnabled;
        this.mtpDraftMax = builder.mtpDraftMax;
        this.vulkanPriority = builder.vulkanPriority;
        this.captureThinkingContent = builder.captureThinkingContent;
        this.toolsJson = normalizeToolsJson(builder.toolsJson);
    }

    public static InferenceOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static InferenceOptions mtpEnabled() {
        return builder().mtpEnabled(true).build();
    }

    public InferenceOptions copy() {
        return builder()
                .mtpEnabled(mtpEnabled)
                .mtpDraftMax(mtpDraftMax)
                .vulkanPriority(vulkanPriority)
                .captureThinkingContent(captureThinkingContent)
                .toolsJson(toolsJson)
                .build();
    }

    public boolean isMtpEnabled() {
        return mtpEnabled;
    }

    public Integer getMtpDraftMax() {
        return mtpDraftMax;
    }

    public Float getVulkanPriority() {
        return vulkanPriority;
    }

    public boolean isCaptureThinkingContent() {
        return captureThinkingContent;
    }

    public String getToolsJson() {
        return toolsJson;
    }

    public static final class Builder {
        private boolean mtpEnabled;
        private Integer mtpDraftMax;
        private Float vulkanPriority;
        private boolean captureThinkingContent;
        private String toolsJson;

        private Builder() {
        }

        public Builder mtpEnabled(boolean value) {
            this.mtpEnabled = value;
            return this;
        }

        public Builder mtpDraftMax(Integer value) {
            if (value != null && (value < 1 || value > MAX_MTP_DRAFT_MAX)) {
                throw new IllegalArgumentException("mtpDraftMax must be between 1 and " + MAX_MTP_DRAFT_MAX);
            }
            this.mtpDraftMax = value;
            return this;
        }

        public Builder vulkanPriority(Float value) {
            if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
                throw new IllegalArgumentException("vulkanPriority must be between 0.0 and 1.0");
            }
            this.vulkanPriority = value;
            return this;
        }

        public Builder captureThinkingContent(boolean value) {
            this.captureThinkingContent = value;
            return this;
        }

        public Builder toolsJson(String value) {
            this.toolsJson = value;
            return this;
        }

        public InferenceOptions build() {
            return new InferenceOptions(this);
        }
    }

    private static String normalizeToolsJson(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
