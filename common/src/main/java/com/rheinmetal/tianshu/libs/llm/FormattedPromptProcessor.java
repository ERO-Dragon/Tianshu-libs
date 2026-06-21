package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppInstructProcessor;
import org.argeo.jjml.llm.LlamaCppSamplerChain;
import org.argeo.jjml.llm.LlamaCppVocabulary;
import org.argeo.jjml.llm.LlamaCppContextState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;

/**
 * Exposes writeFormatted so the full chat history can be formatted once before
 * tokenization. This is required for Jinja chat templates and thinking options.
 */
public class FormattedPromptProcessor extends LlamaCppInstructProcessor {
    private final LlamaCppVocabulary vocabulary;
    private final int[] singleTokenBuffer = new int[1];

    public FormattedPromptProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain) {
        super(context, samplerChain);
        this.vocabulary = context.getModel().getVocabulary();
    }

    public void writePreFormatted(String prompt) {
        writeFormatted(prompt);
    }

    public void writeTokenIds(IntBuffer tokens, boolean lastLogits) {
        writeBatch(tokens, lastLogits);
    }

    public GeneratedToken nextGeneratedToken() {
        if (isGenerationCompleted(0)) return null;

        ByteBuffer nativeBuf = ByteBuffer.allocateDirect(Integer.BYTES);
        nativeBuf.order(ByteOrder.nativeOrder());
        IntBuffer output = nativeBuf.asIntBuffer();

        CompletableFuture<Boolean>[] generationCompleted = newGenerationCompletableFutures();
        CompletableFuture<Boolean> allCompleted = readBatchAsync(new IntBuffer[] { output }, generationCompleted);
        allCompleted.join();

        output.flip();
        if (!output.hasRemaining()) return null;
        int tokenId = output.get();
        return new GeneratedToken(tokenId, deTokenizeSingle(tokenId));
    }

    public void saveContextCheckpoint(LlamaCppContextState savedState) {
        saveContextState(savedState);
    }

    public void loadContextCheckpoint(LlamaCppContextState savedState) {
        loadContextState(savedState);
    }

    private String deTokenizeSingle(int tokenId) {
        singleTokenBuffer[0] = tokenId;
        return vocabulary.deTokenize(IntBuffer.wrap(singleTokenBuffer));
    }
}
