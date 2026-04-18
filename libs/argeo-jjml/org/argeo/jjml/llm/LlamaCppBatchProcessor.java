// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class LlamaCppBatchProcessor {
   private final LlamaCppContext context;
   private LlamaCppSamplerChain samplerChain;
   private LlamaCppNativeSampler validatingSampler;
   private final int NO_OUTPUT_ID;
   private volatile int contextPosition;
   private final int parallelCount;
   private final int[] sequenceIds;
   private final int[] outputIds;
   private final IntBuffer[] tokens;

   public LlamaCppBatchProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain) {
      this(context, samplerChain, (LlamaCppNativeSampler)null, Collections.singleton(0));
   }

   public LlamaCppBatchProcessor(LlamaCppContext context, LlamaCppSamplerChain samplerChain, LlamaCppNativeSampler validatingSampler, Set<Integer> sequenceIds) {
      this.contextPosition = 0;
      Objects.requireNonNull(context);
      Objects.requireNonNull(samplerChain);
      Objects.requireNonNull(sequenceIds);
      this.context = context;
      this.samplerChain = samplerChain;
      this.validatingSampler = validatingSampler;
      this.NO_OUTPUT_ID = this.context.getBatchSize();
      if (sequenceIds.isEmpty()) {
         throw new IllegalArgumentException("There must be at least one sequence");
      } else {
         this.parallelCount = sequenceIds.size();
         this.sequenceIds = new int[this.parallelCount];
         List<Integer> lst = new ArrayList(sequenceIds);
         Collections.sort(lst);

         for(int i = 0; i < lst.size(); ++i) {
            this.sequenceIds[i] = (Integer)lst.get(i);
         }

         this.outputIds = new int[this.parallelCount];
         Arrays.fill(this.outputIds, this.NO_OUTPUT_ID);
         this.tokens = new IntBuffer[this.parallelCount];

         for(int i = 0; i < this.parallelCount; ++i) {
            ByteBuffer directBuf = ByteBuffer.allocateDirect(context.getContextSize() * 4);
            directBuf.order(ByteOrder.nativeOrder());
            this.tokens[i] = directBuf.asIntBuffer();
         }

      }
   }

   private static native int doWrite(long var0, long var2, int var4, IntBuffer[] var5, int[] var6, int[] var7, int[] var8, int[] var9, boolean var10);

   private static native int doWriteArrays(long var0, long var2, int var4, int[][] var5, int[] var6, int[] var7, int[] var8, int[] var9, boolean var10);

   private static native int doRead(long var0, long var2, long var4, int var6, IntBuffer[] var7, int[] var8, int[] var9, int[] var10, int[] var11, CompletionHandler<Integer, Integer> var12);

   private static native int doReadToArrays(long var0, long var2, long var4, int var6, int[][] var7, int[] var8, int[] var9, int[] var10, int[] var11, CompletionHandler<Integer, Integer> var12);

   protected synchronized void writeBatch(IntBuffer buf, boolean thenLastLogits) {
      int tokenCount = buf.remaining();
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
            lastLogits = thenLastLogits;
         } else {
            input.limit(batchSize);
            lastLogits = false;
         }

         buf.position(buf.position() + input.limit());
         this.writeBatch(new IntBuffer[]{input}, lastLogits);
      }

   }

   protected synchronized void writeBatch(IntBuffer[] inputs, boolean lastLogits) throws IllegalArgumentException {
      if (inputs.length != 1 && inputs.length != this.parallelCount) {
         String var10002 = this.parallelCount > 1 ? " either one or " + this.parallelCount + " inputs" : " only one input";
         throw new IllegalArgumentException("There must be" + var10002);
      } else {
         int[] offsets = new int[inputs.length];
         int[] lengths = new int[inputs.length];
         int[][] arrays = new int[inputs.length][];
         boolean allDirect = this.areAllBuffersDirect(inputs, offsets, lengths);
         if (allDirect) {
            this.contextPosition = doWrite(this.context.getAsLong(), this.samplerChain.getAsLong(), this.contextPosition, inputs, offsets, lengths, this.sequenceIds, this.outputIds, lastLogits);
         } else {
            this.buffersToArrays(inputs, offsets, lengths, arrays, true);
            this.contextPosition = doWriteArrays(this.context.getAsLong(), this.samplerChain.getAsLong(), this.contextPosition, arrays, offsets, lengths, this.sequenceIds, this.outputIds, lastLogits);
         }

         for(int i = 0; i < this.parallelCount; ++i) {
            IntBuffer toCopy = inputs.length == 1 ? inputs[0] : inputs[i];
            toCopy.position(inputs.length == 1 ? offsets[0] : offsets[i]);
            toCopy.limit(inputs.length == 1 ? offsets[0] + lengths[0] : offsets[i] + lengths[1]);
            this.tokens[i].put(toCopy);
         }

         if (lastLogits && this.contextPosition > 0) {
            this.samplerChain.reset();
            if (this.validatingSampler != null) {
               this.validatingSampler.reset();
            }
         }

      }
   }

   protected CompletableFuture<Boolean> readBatchAsync(IntBuffer output) {
      if (this.getParallelCount() != 1) {
         throw new UnsupportedOperationException("There are " + this.getParallelCount() + " sequences, while only one is allowed");
      } else {
         return this.readBatchAsync(new IntBuffer[]{output}, (CompletableFuture[])null);
      }
   }

   protected CompletableFuture<Boolean> readBatchAsync(final IntBuffer[] outputs, final CompletableFuture<Boolean>[] generationCompleted) throws IllegalArgumentException {
      if (outputs.length != this.parallelCount) {
         throw new IllegalArgumentException("There must be " + this.parallelCount + " outputs");
      } else if (generationCompleted != null && generationCompleted.length != this.parallelCount) {
         throw new IllegalArgumentException("There must be " + this.parallelCount + " callbacks");
      } else {
         int[] offsets = new int[outputs.length];
         int[] lengths = new int[outputs.length];
         boolean allDirect = this.areAllBuffersDirect(outputs, offsets, lengths);
         final int[][] arrays = allDirect ? null : new int[outputs.length][];
         CompletionHandler<Integer, Integer> completionHandler = new CompletionHandler<Integer, Integer>() {
            public void failed(Throwable exc, Integer sequenceIndex) {
               if (generationCompleted != null) {
                  generationCompleted[sequenceIndex].completeExceptionally(exc);
               }

            }

            public void completed(Integer result, Integer sequenceIndex) {
               IntBuffer output = outputs[sequenceIndex];
               int currentOutputPosition = output.position();
               int currentOutputLimit = output.limit();
               if (arrays != null && !output.hasArray()) {
                  output.put(arrays[sequenceIndex], 0, result);
               } else {
                  output.position(output.position() + result);
               }

               for(int i = 0; i < LlamaCppBatchProcessor.this.parallelCount; ++i) {
                  IntBuffer toCopy = outputs[i];
                  toCopy.position(currentOutputPosition);
                  toCopy.limit(currentOutputPosition + result);
                  LlamaCppBatchProcessor.this.tokens[i].put(toCopy);
                  toCopy.limit(currentOutputLimit);
               }

               if (generationCompleted != null) {
                  int outputId = LlamaCppBatchProcessor.this.outputIds[sequenceIndex];
                  generationCompleted[sequenceIndex].complete(outputId == LlamaCppBatchProcessor.this.NO_OUTPUT_ID);
               }

            }
         };
         CompletableFuture<Boolean> allCompleted = CompletableFuture.supplyAsync(() -> {
            synchronized(this) {
               if (allDirect) {
                  this.contextPosition = doRead(this.context.getAsLong(), this.samplerChain.getAsLong(), this.validatingSampler != null ? this.validatingSampler.getAsLong() : 0L, this.contextPosition, outputs, offsets, lengths, this.sequenceIds, this.outputIds, completionHandler);
               } else {
                  this.buffersToArrays(outputs, offsets, lengths, arrays, false);
                  this.contextPosition = doReadToArrays(this.context.getAsLong(), this.samplerChain.getAsLong(), this.validatingSampler != null ? this.validatingSampler.getAsLong() : 0L, this.contextPosition, arrays, offsets, lengths, this.sequenceIds, this.outputIds, completionHandler);
               }

               boolean allGenerationCompleted = true;

               for(int i = 0; i < this.outputIds.length; ++i) {
                  if (this.NO_OUTPUT_ID != this.outputIds[i]) {
                     allGenerationCompleted = false;
                     break;
                  }
               }

               return allGenerationCompleted;
            }
         });
         return allCompleted;
      }
   }

   private boolean areAllBuffersDirect(IntBuffer[] buffers, int[] offsets, int[] lengths) {
      boolean allDirect = true;

      for(int i = 0; i < buffers.length; ++i) {
         IntBuffer buf = buffers[i];
         if (buf == null) {
            offsets[i] = 0;
            lengths[i] = 0;
         } else {
            if (!buf.isDirect()) {
               allDirect = false;
               break;
            }

            offsets[i] = buf.position();
            lengths[i] = buf.remaining();
         }
      }

      return allDirect;
   }

   private void buffersToArrays(IntBuffer[] buffers, int[] offsets, int[] lengths, int[][] arrays, boolean input) {
      for(int i = 0; i < buffers.length; ++i) {
         IntBuffer buf = buffers[i];
         if (buf == null) {
            offsets[i] = 0;
            lengths[i] = 0;
            arrays[i] = null;
         } else if (buf.hasArray()) {
            offsets[i] = buf.arrayOffset() + buf.position();
            lengths[i] = buf.remaining();
            arrays[i] = buf.array();
         } else {
            offsets[i] = 0;
            lengths[i] = buf.remaining();
            int[] arr = new int[lengths[i]];
            if (input) {
               buf.get(arr);
            }

            arrays[i] = arr;
         }
      }

   }

   int getParallelCount() {
      return this.parallelCount;
   }

   protected LlamaCppContext getContext() {
      return this.context;
   }

   protected LlamaCppModel getModel() {
      return this.context.getModel();
   }

   protected boolean isGenerationCompleted(int sequenceIndex) {
      return this.outputIds[sequenceIndex] == this.NO_OUTPUT_ID;
   }

   public synchronized void saveContextState(LlamaCppContextState savedState) {
      synchronized(this.context) {
         savedState.save(this.context, this.contextPosition);
      }
   }

   public synchronized void loadContextState(LlamaCppContextState savedState) {
      synchronized(this.context) {
         int savedContextPosition = savedState.load(this.context);
         this.contextPosition = savedContextPosition;
         Arrays.fill(this.outputIds, savedContextPosition - 1);
      }
   }

   public synchronized void saveStateFile(Path path) throws IOException {
      if (this.parallelCount != 1) {
         throw new UnsupportedOperationException("Session files are not supported for parallel batches");
      } else {
         synchronized(this.context) {
            Objects.requireNonNull(path);
            this.context.saveStateFile(path, this.tokens[0]);
         }
      }
   }

   public synchronized void loadStateFile(Path path) throws IOException {
      if (this.parallelCount != 1) {
         throw new UnsupportedOperationException("Session files are not supported for parallel batches");
      } else {
         synchronized(this.context) {
            Objects.requireNonNull(path);
            int position = this.context.loadStateFile(path, this.tokens[0]);
            this.contextPosition = position;
         }
      }
   }

   protected CompletableFuture<Boolean>[] newGenerationCompletableFutures() {
      CompletableFuture<Boolean>[] generationCompleted = (CompletableFuture[])this.createArr(CompletableFuture.class);

      for(int i = 0; i < generationCompleted.length; ++i) {
         generationCompleted[i] = new CompletableFuture();
      }

      return generationCompleted;
   }

   private <T> T[] createArr(Class<T> clz) {
      return (T[])((Object[])Array.newInstance(clz, this.getParallelCount()));
   }

   public static <T> CompletionStage<Object> anyOf(List<CompletionStage<T>> css) {
      return CompletableFuture.anyOf((CompletableFuture[])css.stream().map(CompletionStage::toCompletableFuture).toArray((x$0) -> new CompletableFuture[x$0]));
   }

   public static <T> CompletionStage<Void> allOf(List<CompletionStage<T>> css) {
      return CompletableFuture.allOf((CompletableFuture[])css.stream().map(CompletionStage::toCompletableFuture).toArray((x$0) -> new CompletableFuture[x$0]));
   }
}
