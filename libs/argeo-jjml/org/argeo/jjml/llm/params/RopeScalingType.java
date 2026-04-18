// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

import java.util.function.IntSupplier;

public enum RopeScalingType implements IntSupplier {
   LLAMA_ROPE_SCALING_TYPE_UNSPECIFIED(-1),
   LLAMA_ROPE_SCALING_TYPE_NONE(0),
   LLAMA_ROPE_SCALING_TYPE_LINEAR(1),
   LLAMA_ROPE_SCALING_TYPE_YARN(2);

   private int code;

   private RopeScalingType(int code) {
      this.code = code;
   }

   public int getAsInt() {
      return this.code;
   }

   public static RopeScalingType byCode(int code) throws IllegalArgumentException {
      for(RopeScalingType type : values()) {
         if (type.code == code) {
            return type;
         }
      }

      throw new IllegalArgumentException("Unkown pooling type code : " + code);
   }
}
