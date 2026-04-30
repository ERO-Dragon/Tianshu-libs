// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.util.function.LongSupplier;

public class LlamaCppNativeSampler implements LongSupplier, AutoCloseable, Cloneable {
   private final long pointer;
   private LlamaCppSamplerChain samplerChain = null;

   LlamaCppNativeSampler(long pointer) {
      this.pointer = pointer;
   }

   private native void doReset();

   private native void doDestroy();

   private native long doClone();

   public void close() throws RuntimeException {
      if (this.samplerChain == null) {
         this.doDestroy();
      } else {
         throw new IllegalStateException("This sampler cannot be closed as it belong to chain " + String.valueOf(this.samplerChain));
      }
   }

   public long getAsLong() {
      return this.pointer;
   }

   protected Object clone() throws CloneNotSupportedException {
      return new LlamaCppNativeSampler(this.doClone());
   }

   public void reset() {
      this.doReset();
   }

   void setSamplerChain(LlamaCppSamplerChain currentChain) {
      this.samplerChain = currentChain;
   }

   LlamaCppSamplerChain getSamplerChain() {
      return this.samplerChain;
   }
}
