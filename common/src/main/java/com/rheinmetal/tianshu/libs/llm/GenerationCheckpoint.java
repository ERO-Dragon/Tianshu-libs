package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppContextState;

final class GenerationCheckpoint {
    enum Kind {
        STANDARD,
        MTP_TARGET
    }

    private final Kind kind;
    private final LlamaCppContextState savedState;
    private final int promptTokenCount;
    private final int generatedTokenCount;
    private final int restoredTokenCount;

    private GenerationCheckpoint(Kind kind,
                                 LlamaCppContextState savedState,
                                 int promptTokenCount,
                                 int generatedTokenCount,
                                 int restoredTokenCount) {
        this.kind = kind;
        this.savedState = savedState;
        this.promptTokenCount = promptTokenCount;
        this.generatedTokenCount = generatedTokenCount;
        this.restoredTokenCount = restoredTokenCount;
    }

    static GenerationCheckpoint captureStandard(FormattedPromptProcessor processor,
                                                int promptTokenCount,
                                                int generatedTokenCount) {
        LlamaCppContextState savedState = new LlamaCppContextState.ByteBufferSavedState();
        processor.saveContextCheckpoint(savedState);
        return new GenerationCheckpoint(
                Kind.STANDARD,
                savedState,
                promptTokenCount,
                generatedTokenCount,
                promptTokenCount + generatedTokenCount
        );
    }

    static GenerationCheckpoint captureMtpTarget(LlamaCppContextState savedState,
                                                 int promptTokenCount,
                                                 int generatedTokenCount,
                                                 int restoredTokenCount) {
        if (generatedTokenCount < 1) {
            throw new IllegalArgumentException("MTP checkpoints require at least one generated token");
        }
        if (restoredTokenCount < promptTokenCount) {
            throw new IllegalArgumentException("restoredTokenCount cannot be before the prompt");
        }
        if (restoredTokenCount >= promptTokenCount + generatedTokenCount) {
            throw new IllegalArgumentException("MTP checkpoints must leave at least one emitted token for tail replay");
        }
        return new GenerationCheckpoint(Kind.MTP_TARGET, savedState, promptTokenCount, generatedTokenCount, restoredTokenCount);
    }

    void loadInto(FormattedPromptProcessor processor) {
        processor.loadContextCheckpoint(savedState);
    }

    int loadInto(LlamaCppContext context) {
        return savedState.load(context);
    }

    boolean isStandard() {
        return kind == Kind.STANDARD;
    }

    boolean isMtpTarget() {
        return kind == Kind.MTP_TARGET;
    }

    int generatedTokenCount() {
        return generatedTokenCount;
    }

    int promptTokenCount() {
        return promptTokenCount;
    }

    int restoredTokenCount() {
        return restoredTokenCount;
    }
}
