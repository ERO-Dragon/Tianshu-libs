// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

public class LlamaCppTextProcessor extends LlamaCppBatchProcessor {
   private final LlamaCppVocabulary vocabulary;

   public LlamaCppTextProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain) {
      this(context, samplerChain, (LlamaCppNativeSampler)null, Collections.singleton(0));
   }

   public LlamaCppTextProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain, LlamaCppNativeSampler validatingSampler, Set<Integer> sequenceIds) {
      super(context, samplerChain, validatingSampler, sequenceIds);
      this.vocabulary = context.getModel().getVocabulary();
   }

   public String processSingleBatch(String systemPrompt) {
      return this.processBatch(systemPrompt);
   }

   public String processBatch(String prompt) {
      return this.processBatch(prompt, (String[])null, (String)null);
   }

   public String processBatch(String prompt, String[] parameters, String postPrompt) {
      IntBuffer promptTokens = this.vocabulary.tokenize(prompt);

      assert promptTokens.position() == 0;

      int tokenCount = promptTokens.limit();
      int[] promptArr = promptTokens.array();
      int outputMax = this.getContext().getBatchSize();
      int requiredContextSize = tokenCount + outputMax * this.getParallelCount() * 10;
      int contextSize = this.getContext().getContextSize();
      if (this.getContext().getContextSize() < requiredContextSize) {
         throw new IllegalArgumentException("The required KV cache size " + requiredContextSize + " is not big enough, only " + contextSize + " available. Reduce parallel or increase context size.");
      } else {
         boolean direct = false;
         IntBuffer buf;
         if (direct) {
            ByteBuffer directBuf = ByteBuffer.allocateDirect(requiredContextSize * 4);
            directBuf.order(ByteOrder.nativeOrder());
            buf = directBuf.asIntBuffer();
         } else {
            buf = IntBuffer.allocate(requiredContextSize);
         }

         int batchSize = this.getContext().getBatchSize();
         boolean tokenList = true;
         if (tokenList) {
            int batchCount = tokenCount / batchSize;
            if (tokenCount % batchSize != 0) {
               ++batchCount;
            }

            for(int i = 0; i < batchCount; ++i) {
               IntBuffer input = buf.slice();
               boolean lastLogits;
               if (i == batchCount - 1) {
                  input.limit(tokenCount % batchSize == 0 ? batchSize : tokenCount % batchSize);
                  lastLogits = parameters == null;
               } else {
                  input.limit(batchSize);
                  lastLogits = false;
               }

               buf.position(buf.position() + input.limit());
               input.put(promptArr, i * batchSize, input.limit());
               input.flip();
               this.writeBatch(new IntBuffer[]{input}, lastLogits);
            }

            if (parameters != null) {
               if (parameters.length != this.getParallelCount()) {
                  throw new IllegalArgumentException("Parameters count different from sequence count");
               }

               IntBuffer[] inputs = new IntBuffer[this.getParallelCount()];

               for(int i = 0; i < this.getParallelCount(); ++i) {
                  IntBuffer parametersTokens = this.vocabulary.tokenize(parameters[i]);
                  if (parametersTokens.remaining() * this.getParallelCount() > batchSize) {
                     throw new IllegalArgumentException("Parameter '" + parameters[i] + "' is too long.");
                  }

                  inputs[i] = buf.slice();
                  inputs[i].limit(parametersTokens.remaining());
                  buf.position(buf.position() + inputs[i].limit());
                  inputs[i].put(parametersTokens.array(), 0, inputs[i].limit());
                  inputs[i].flip();
               }

               this.writeBatch(inputs, postPrompt == null);
            }

            if (postPrompt != null) {
               IntBuffer postPromptTokens = this.vocabulary.tokenize(postPrompt);
               if (postPromptTokens.remaining() > batchSize) {
                  throw new IllegalArgumentException("Post prompt '" + postPrompt + "' is too long.");
               }

               IntBuffer input = buf.slice();
               input.limit(postPromptTokens.remaining());
               buf.position(buf.position() + input.limit());
               input.put(postPromptTokens.array(), 0, input.limit());
               input.flip();
               this.writeBatch(new IntBuffer[]{input}, true);
            }
         } else {
            IntBuffer input = buf.slice();
            this.vocabulary.tokenize(prompt, input, true, true);
            buf.position(input.position());
            input.flip();
            this.writeBatch(new IntBuffer[]{input}, true);
         }

         StringBuffer[] outputStrings = new StringBuffer[this.getParallelCount()];

         for(int i = 0; i < outputStrings.length; ++i) {
            outputStrings[i] = new StringBuffer();
         }

         boolean reading = true;

         while(reading) {
            IntBuffer[] outputs = new IntBuffer[this.getParallelCount()];

            for(int i = 0; i < this.getParallelCount(); ++i) {
               if (this.isGenerationCompleted(i)) {
                  outputs[i] = null;
               } else {
                  IntBuffer output = buf.slice();
                  output.limit(outputMax);
                  outputs[i] = output;
                  buf.position(buf.position() + output.limit());
               }
            }

            long begin = System.nanoTime();
            CompletableFuture<Boolean>[] generationCompleted = this.newGenerationCompletableFutures();
            CompletableFuture<Boolean> allCompleted = this.readBatchAsync(outputs, generationCompleted);
            allCompleted.join();
            long end = System.nanoTime();
            System.out.println("Read  batch in " + (end - begin) / 1000000L + " ms.");
            int sequencesLeft = 0;

            for(int i = 0; i < outputs.length; ++i) {
               IntBuffer output = outputs[i];
               if (output != null) {
                  output.flip();
                  String outputStr = this.vocabulary.deTokenize(output);
                  outputStrings[i].append(outputStr);
               }

               if (!this.isGenerationCompleted(i)) {
                  ++sequencesLeft;
               }
            }

            if (sequencesLeft == 0) {
               break;
            }

            System.out.println(sequencesLeft + " sequences left");
            if (buf.position() + sequencesLeft * outputMax > buf.capacity()) {
               System.err.println("Main buffer will be full, aborting...");
               break;
            }
         }

         StringJoiner res = new StringJoiner("\n\n\n---------------------------------------------------------------\n\n\n");

         for(int i = 0; i < outputStrings.length; ++i) {
            res.add(outputStrings[i]);
         }

         return res.toString();
      }
   }
}
