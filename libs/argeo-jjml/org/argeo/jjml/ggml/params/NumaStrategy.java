// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.ggml.params;

import java.util.function.IntSupplier;

public enum NumaStrategy implements IntSupplier {
   GGML_NUMA_STRATEGY_DISABLED(0),
   GGML_NUMA_STRATEGY_DISTRIBUTE(1),
   GGML_NUMA_STRATEGY_ISOLATE(2),
   GGML_NUMA_STRATEGY_NUMACTL(3),
   GGML_NUMA_STRATEGY_MIRROR(4);

   private int code;

   private NumaStrategy(int code) {
      this.code = code;
   }

   public int getAsInt() {
      return this.code;
   }

   public static NumaStrategy byCode(int code) throws IllegalArgumentException {
      for(NumaStrategy type : values()) {
         if (type.code == code) {
            return type;
         }
      }

      throw new IllegalArgumentException("Unkown code : " + code);
   }
}
