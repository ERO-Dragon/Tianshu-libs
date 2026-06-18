package com.rheinmetal.tianshu.libs.llm;

public enum InferenceEventType {
    QUEUED,
    STARTED,
    COLD_RESUME_STARTED,
    COLD_RESUME_COMPLETED,
    PREFILL_STARTED,
    PREFILL_COMPLETED,
    GENERATION_STARTED,
    SUSPENDED,
    COMPLETED,
    CANCELLED,
    FAILED
}
