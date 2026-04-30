// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class LlamaCppInstructProcessor extends LlamaCppBatchProcessor {
   private final LlamaCppVocabulary vocabulary;

   public LlamaCppInstructProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain) {
      super(context, samplerChain);
      this.vocabulary = context.getModel().getVocabulary();
   }

   public void write(Supplier<String> role, String message) {
      Objects.requireNonNull(message);
      this.write(new LlamaCppChatMessage(role, message));
   }

   public void write(String role, String message) {
      Objects.requireNonNull(message);
      this.write(new LlamaCppChatMessage(role, message));
   }

   public void write(LlamaCppChatMessage message) {
      Objects.requireNonNull(message);
      String prompt = this.getModel().formatChatMessages(new LlamaCppChatMessage[]{message});
      this.writeFormatted(prompt);
   }

   protected void writeFormatted(String prompt) {
      IntBuffer promptTokens = this.vocabulary.tokenize(prompt);

      assert promptTokens.position() == 0;

      int tokenCount = promptTokens.limit();
      int[] promptArr = promptTokens.array();
      int outputMax = this.getContext().getBatchSize();
      int requiredContextSize = tokenCount + outputMax * this.getParallelCount();
      int contextSize = this.getContext().getContextSize();
      if (this.getContext().getContextSize() < requiredContextSize) {
         throw new IllegalArgumentException("The required KV cache size " + requiredContextSize + " is not big enough, only " + contextSize + " available. Reduce parallel or increase context size.");
      } else {
         ByteBuffer nativeBuf = ByteBuffer.allocateDirect(requiredContextSize * 4);
         nativeBuf.order(ByteOrder.nativeOrder());
         IntBuffer buf = nativeBuf.asIntBuffer();
         int batchSize = this.getContext().getBatchSize();
         int batchCount = tokenCount / batchSize;
         if (tokenCount % batchSize != 0) {
            ++batchCount;
         }

         for(int i = 0; i < batchCount; ++i) {
            IntBuffer input = buf.slice();
            boolean lastLogits;
            if (i == batchCount - 1) {
               input.limit(tokenCount % batchSize == 0 ? batchSize : tokenCount % batchSize);
               lastLogits = true;
            } else {
               input.limit(batchSize);
               lastLogits = false;
            }

            buf.position(buf.position() + input.limit());
            input.put(promptArr, i * batchSize, input.limit());
            input.flip();
            this.writeBatch(new IntBuffer[]{input}, lastLogits);
         }

      }
   }

   public String nextToken() {
      if (this.isGenerationCompleted(0)) {
         return null;
      } else {
         ByteBuffer nativeBuf = ByteBuffer.allocateDirect(4);
         nativeBuf.order(ByteOrder.nativeOrder());
         IntBuffer output = nativeBuf.asIntBuffer();
         CompletableFuture<Boolean>[] generationCompleted = this.newGenerationCompletableFutures();
         CompletableFuture<Boolean> allCompleted = this.readBatchAsync(new IntBuffer[]{output}, generationCompleted);
         allCompleted.join();
         output.flip();
         String outputStr = this.vocabulary.deTokenize(output);
         return outputStr;
      }
   }

   public void readMessage(PrintStream out) throws IOException {
      out.flush();
      this.readMessage((Writer)(new PrintWriter(out, false, StandardCharsets.UTF_8)));
   }

   public void readMessage(Writer writer) throws IOException {
      boolean reading = true;

      while(reading) {
         String outputStr = this.nextToken();
         if (outputStr == null) {
            break;
         }

         writer.write(outputStr);
         writer.flush();
      }

   }
}
