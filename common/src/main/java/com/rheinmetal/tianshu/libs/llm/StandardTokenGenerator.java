package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppSamplerChain;

import java.nio.IntBuffer;

final class StandardTokenGenerator implements CheckpointableTokenGenerator {
    private final LlamaCppContext context;
    private final LlamaCppSamplerChain samplerChain;
    private final FormattedPromptProcessor processor;
    private final int promptTokenCount;
    private int generatedTokenCount;

    StandardTokenGenerator(LlamaCppContext context, LlamaCppSamplerChain samplerChain, int[] promptTokens) {
        this(context, samplerChain, promptTokens, promptTokens.length, 0);
    }

    StandardTokenGenerator(LlamaCppContext context,
                           LlamaCppSamplerChain samplerChain,
                           int[] tokenIds,
                           int promptTokenCount,
                           int generatedTokenCount) {
        this.context = context;
        this.samplerChain = samplerChain;
        validateTokenBoundary(tokenIds, promptTokenCount, generatedTokenCount);
        this.promptTokenCount = promptTokenCount;
        this.generatedTokenCount = generatedTokenCount;
        this.processor = new FormattedPromptProcessor(context, samplerChain);
        writeTokenIds(tokenIds, true);
    }

    StandardTokenGenerator(LlamaCppContext context,
                           LlamaCppSamplerChain samplerChain,
                           GenerationCheckpoint checkpoint,
                           int[] replayTokens) {
        this.context = context;
        this.samplerChain = samplerChain;
        this.promptTokenCount = checkpoint.promptTokenCount();
        this.processor = new FormattedPromptProcessor(context, samplerChain);
        checkpoint.loadInto(processor);
        writeTokenIds(replayTokens, true);
        this.generatedTokenCount = checkpoint.generatedTokenCount() + replayTokens.length;
    }

    private void validateTokenBoundary(int[] tokenIds, int promptTokenCount, int generatedTokenCount) {
        if (tokenIds == null || tokenIds.length == 0) {
            throw new IllegalArgumentException("At least one token is required to initialize standard generation");
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

    private void writeTokenIds(int[] tokenIds, boolean lastLogits) {
        if (tokenIds == null || tokenIds.length == 0) {
            throw new IllegalArgumentException("At least one token is required to initialize standard generation");
        }
        processor.writeTokenIds(IntBuffer.wrap(tokenIds), lastLogits);
    }

    @Override
    public GeneratedToken next() {
        GeneratedToken token = processor.nextGeneratedToken();
        if (token != null) generatedTokenCount++;
        return token;
    }

    @Override
    public GenerationCheckpoint checkpoint() {
        return GenerationCheckpoint.captureStandard(processor, promptTokenCount, generatedTokenCount);
    }

    @Override
    public void close() {
        try { context.close(); } catch (Exception ignored) {}
        try { samplerChain.close(); } catch (Exception ignored) {}
    }
}
