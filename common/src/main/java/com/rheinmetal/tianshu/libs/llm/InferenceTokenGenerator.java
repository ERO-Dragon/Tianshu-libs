package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.SpeculativeStats;

interface InferenceTokenGenerator extends AutoCloseable {
    GeneratedToken next();

    default GeneratedToken next(int maxTokensRemaining) {
        return next();
    }

    default boolean isMtp() {
        return false;
    }

    default int getMtpDraftMax() {
        return 0;
    }

    default SpeculativeStats getSpeculativeStats() {
        return null;
    }

    @Override
    void close();
}
