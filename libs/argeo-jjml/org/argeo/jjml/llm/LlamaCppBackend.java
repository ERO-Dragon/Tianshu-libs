// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import org.argeo.jjml.ggml.params.NumaStrategy;
import org.argeo.jjml.llm.params.ContextParams;
import org.argeo.jjml.llm.params.ModelParams;

public class LlamaCppBackend {
   static native void doNumaInit(int var0);

   static native void doDestroy();

   static native ModelParams newModelParams();

   static native ContextParams newContextParams();

   public static native boolean supportsMmap();

   public static native boolean supportsMlock();

   public static native boolean supportsGpuOffload();

   public static void numaInit(NumaStrategy numaStrategy) {
      if (numaStrategy != null) {
         doNumaInit(numaStrategy.getAsInt());
      }

   }

   public static void destroy() {
      doDestroy();
   }

   private LlamaCppBackend() {
   }

   static {
      LlamaCppNative.ensureLibrariesLoaded();
      Runtime.getRuntime().addShutdownHook(new Thread(() -> destroy(), "Destroy llama.cpp backend"));
   }
}
