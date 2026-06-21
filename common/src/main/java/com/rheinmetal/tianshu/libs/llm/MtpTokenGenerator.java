package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppContextState;
import org.argeo.jjml.llm.LlamaCppSamplerChain;
import org.argeo.jjml.llm.LlamaCppSpeculativeProcessor;
import org.argeo.jjml.llm.LlamaCppVocabulary;
import org.argeo.jjml.llm.SpeculativeParams;
import org.argeo.jjml.llm.SpeculativeStats;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;

final class MtpTokenGenerator implements CheckpointableTokenGenerator {
    private final LlamaCppContext context;
    private final LlamaCppSamplerChain samplerChain;
    private final LlamaCppSpeculativeProcessor processor;
    private final LlamaCppVocabulary vocabulary;
    private final int draftMax;
    private final int readWindow;
    private final int promptTokenCount;
    private final int[] singleTokenBuffer = new int[1];
    private final Deque<GeneratedToken> bufferedTokens = new ArrayDeque<>();
    private int generatedTokenCount;
    private int stableGeneratedTokenCount;

    MtpTokenGenerator(LlamaCppContext context,
                      LlamaCppSamplerChain samplerChain,
                      int[] promptTokens,
                      int draftMax) {
        this(context, samplerChain, promptTokens, promptTokens.length, 0, null, draftMax);
    }

    MtpTokenGenerator(LlamaCppContext context,
                      LlamaCppSamplerChain samplerChain,
                      int[] tokenIds,
                      int promptTokenCount,
                      int generatedTokenCount,
                      GenerationCheckpoint checkpoint,
                      int draftMax) {
        this.context = context;
        this.samplerChain = samplerChain;
        this.vocabulary = context.getModel().getVocabulary();
        this.draftMax = draftMax;
        this.readWindow = Math.max(1, draftMax + 1);
        validateTokenBoundary(tokenIds, promptTokenCount, generatedTokenCount);
        this.promptTokenCount = promptTokenCount;
        this.generatedTokenCount = generatedTokenCount;
        this.stableGeneratedTokenCount = generatedTokenCount;
        this.processor = new LlamaCppSpeculativeProcessor(context, samplerChain, SpeculativeParams.draftMtp(draftMax));
        if (checkpoint == null) {
            this.processor.begin(IntBuffer.wrap(tokenIds));
        } else {
            if (!checkpoint.isMtpTarget()) {
                throw new IllegalArgumentException("MTP restore requires an MTP target checkpoint");
            }
            int loadedPosition = checkpoint.loadInto(context);
            if (loadedPosition != checkpoint.restoredTokenCount()) {
                throw new IllegalStateException("Loaded MTP checkpoint position " + loadedPosition
                        + " does not match restoredTokenCount " + checkpoint.restoredTokenCount());
            }
            this.processor.beginFromRestoredTarget(IntBuffer.wrap(tokenIds), checkpoint.restoredTokenCount());
        }
    }

    private void validateTokenBoundary(int[] tokenIds, int promptTokenCount, int generatedTokenCount) {
        if (tokenIds == null || tokenIds.length == 0) {
            throw new IllegalArgumentException("At least one token is required to initialize MTP generation");
        }
        if (promptTokenCount < 1) {
            throw new IllegalArgumentException("promptTokenCount must be positive");
        }
        if (generatedTokenCount < 0) {
            throw new IllegalArgumentException("generatedTokenCount must not be negative");
        }
        if (promptTokenCount + generatedTokenCount != tokenIds.length) {
            throw new IllegalArgumentException("tokenIds must contain exactly promptTokenCount + generatedTokenCount tokens");
        }
    }

    @Override
    public GeneratedToken next() {
        return next(readWindow);
    }

    @Override
    public GeneratedToken next(int maxTokensRemaining) {
        if (bufferedTokens.isEmpty()) {
            if (maxTokensRemaining <= 0) return null;
            int[] tokens = processor.readArray(Math.min(readWindow, maxTokensRemaining));
            if (tokens.length == 0) return null;
            for (int i = 0; i < tokens.length; i++) {
                bufferedTokens.addLast(new GeneratedToken(tokens[i], deTokenizeSingle(tokens[i])));
            }
            stableGeneratedTokenCount = generatedTokenCount + tokens.length;
        }
        GeneratedToken token = bufferedTokens.pollFirst();
        if (token != null) generatedTokenCount++;
        return token;
    }

    private String deTokenizeSingle(int token) {
        singleTokenBuffer[0] = token;
        return vocabulary.deTokenize(IntBuffer.wrap(singleTokenBuffer));
    }

    @Override
    public boolean isMtp() {
        return true;
    }

    @Override
    public int getMtpDraftMax() {
        return draftMax;
    }

    @Override
    public SpeculativeStats getSpeculativeStats() {
        return processor.getStats();
    }

    @Override
    public GenerationCheckpoint checkpoint() {
        if (!bufferedTokens.isEmpty()) return null;
        if (stableGeneratedTokenCount < 1) return null;

        LlamaCppContextState savedState = new LlamaCppContextState.ByteBufferSavedState();
        int restoredTokenCount = promptTokenCount + stableGeneratedTokenCount - 1;
        savedState.save(context, restoredTokenCount);
        return GenerationCheckpoint.captureMtpTarget(
                savedState,
                promptTokenCount,
                stableGeneratedTokenCount,
                restoredTokenCount
        );
    }

    @Override
    public void close() {
        try { processor.close(); } catch (Exception ignored) {}
        try { context.close(); } catch (Exception ignored) {}
        try { samplerChain.close(); } catch (Exception ignored) {}
    }
}
