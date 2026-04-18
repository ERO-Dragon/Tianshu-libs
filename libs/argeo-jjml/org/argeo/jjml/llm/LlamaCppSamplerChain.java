// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

public class LlamaCppSamplerChain extends LlamaCppNativeSampler {
   private static native long doInit();

   private native void doAddSampler(LlamaCppNativeSampler var1);

   private native long doRemoveSampler(int var1);

   private native long doGetSampler(int var1);

   private native int doGetSize();

   public LlamaCppSamplerChain() {
      super(doInit());
   }

   public LlamaCppSamplerChain(LlamaCppNativeSampler... samplers) {
      this();

      for(LlamaCppNativeSampler sampler : samplers) {
         this.addSampler(sampler);
      }

   }

   public void addSampler(LlamaCppNativeSampler sampler) {
      if (sampler.getSamplerChain() != null) {
         throw new IllegalStateException("A sampler cannot be used by two chains or added twice, it should be removed first.");
      } else {
         this.doAddSampler(sampler);
         sampler.setSamplerChain(this);
      }
   }
}
