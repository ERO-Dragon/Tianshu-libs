// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import org.argeo.jjml.llm.params.PoolingType;

public class LlamaCppEmbeddingProcessor {
   private final LlamaCppContext context;

   public LlamaCppEmbeddingProcessor(LlamaCppContext context) {
      this.context = context;
   }

   private static native void doProcessEmbeddings(long var0, int[][] var2, float[] var3);

   public float[][] processEmbeddings(List<String> prompts) {
      IntBuffer[] tokenLists = new IntBuffer[prompts.size()];

      for(int i = 0; i < prompts.size(); ++i) {
         String prompt = (String)prompts.get(i);
         IntBuffer tokenList = this.context.getModel().getVocabulary().tokenize(prompt);
         tokenLists[i] = tokenList;
      }

      return this.processEmbeddings(tokenLists);
   }

   public float[][] processEmbeddings(IntBuffer[] inputs) {
      PoolingType poolingType = this.context.getPoolingType();
      int n_embd_count = 0;
      if (PoolingType.LLAMA_POOLING_TYPE_NONE.equals(poolingType)) {
         for(IntBuffer tokenList : inputs) {
            n_embd_count += tokenList.remaining();
         }
      } else {
         n_embd_count = inputs.length;
      }

      int n_embd = this.context.getModel().getEmbeddingSize();
      float[] emb = new float[n_embd_count * n_embd];
      Arrays.fill(emb, 0.0F);
      int[][] tokens = new int[inputs.length][];

      for(int i = 0; i < inputs.length; ++i) {
         tokens[i] = inputs[i].array();
      }

      doProcessEmbeddings(this.context.getAsLong(), tokens, emb);
      float[][] res = new float[n_embd_count][];
      int j = 0;

      do {
         float[] arr = new float[n_embd];
         int i = 0;

         do {
            arr[i] = emb[j * n_embd + i];
            ++i;
         } while(i != n_embd);

         res[j] = arr;
         ++j;
      } while(j != n_embd_count);

      return res;
   }

   protected LlamaCppContext getContext() {
      return this.context;
   }
}
