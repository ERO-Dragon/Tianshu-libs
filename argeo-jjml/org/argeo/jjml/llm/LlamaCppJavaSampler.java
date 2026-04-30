// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public interface LlamaCppJavaSampler {
   long apply(ByteBuffer var1, long var2, long var4, boolean var6);

   default void accept(int token) {
   }

   default void reset() {
   }

   default String getName() {
      return this.getClass().getName();
   }

   public static class SimpleGreedy implements LlamaCppJavaSampler {
      public SimpleGreedy() {
      }

      public long apply(ByteBuffer buf, long size, long selected, boolean sorted) {
         ByteBuffer b = buf.duplicate();
         b.order(ByteOrder.nativeOrder());
         long count = 0L;
         long res = 0L;

         for(float bestLogit = Float.NEGATIVE_INFINITY; count < size; ++count) {
            b.getInt();
            float logit = b.getFloat();
            b.getFloat();
            if (count == 0L) {
               bestLogit = logit;
            } else if (logit > bestLogit) {
               res = count;
               bestLogit = logit;
            }
         }

         return res;
      }
   }
}
