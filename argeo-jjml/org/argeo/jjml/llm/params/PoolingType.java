// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

import java.util.function.IntSupplier;

public enum PoolingType implements IntSupplier {
   LLAMA_POOLING_TYPE_UNSPECIFIED(-1),
   LLAMA_POOLING_TYPE_NONE(0),
   LLAMA_POOLING_TYPE_MEAN(1),
   LLAMA_POOLING_TYPE_CLS(2),
   LLAMA_POOLING_TYPE_LAST(3);

   private int code;

   private PoolingType(int code) {
      this.code = code;
   }

   public int getAsInt() {
      return this.code;
   }

   public static PoolingType byCode(int code) throws IllegalArgumentException {
      for(PoolingType type : values()) {
         if (type.code == code) {
            return type;
         }
      }

      throw new IllegalArgumentException("Unkown pooling type code : " + code);
   }
}
