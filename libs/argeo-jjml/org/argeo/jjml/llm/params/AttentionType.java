// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

import java.util.function.IntSupplier;

public enum AttentionType implements IntSupplier {
   LLAMA_ATTENTION_TYPE_UNSPECIFIED(-1),
   LLAMA_ATTENTION_TYPE_CAUSAL(0),
   LLAMA_ATTENTION_TYPE_NON_CAUSAL(1);

   private int code;

   private AttentionType(int code) {
      this.code = code;
   }

   public int getAsInt() {
      return this.code;
   }

   public static AttentionType byCode(int code) throws IllegalArgumentException {
      for(AttentionType type : values()) {
         if (type.code == code) {
            return type;
         }
      }

      throw new IllegalArgumentException("Unkown code : " + code);
   }
}
