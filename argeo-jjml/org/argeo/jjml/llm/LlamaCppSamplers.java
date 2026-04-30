// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.charset.StandardCharsets;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

public class LlamaCppSamplers {
   private static native long doInitGreedy();

   private static native long doInitPenalties(int var0, float var1, float var2, float var3, boolean var4, boolean var5);

   private static native long doInitTopK(int var0);

   private static native long doInitTopP(float var0, long var1);

   private static native long doInitMinP(float var0, long var1);

   private static native long doInitTypicalP(float var0, long var1);

   private static native long doInitTempExt(float var0, float var1, float var2);

   private static native long doInitTemp(float var0);

   private static native long doInitDist();

   private static native long doInitDist(int var0);

   private static native long doInitGrammar(LlamaCppModel var0, byte[] var1, byte[] var2);

   private static native long doInitJavaSampler(LlamaCppJavaSampler var0);

   public static LlamaCppSamplerChain newDefaultSampler() {
      return newDefaultSampler(false);
   }

   public static LlamaCppSamplerChain newDefaultSampler(boolean withTemp) {
      return newDefaultSampler(withTemp ? new DefaultSamplerChainParams() : new DefaultSamplerChainParams(0));
   }

   public static LlamaCppSamplerChain newDefaultSampler(DefaultSamplerChainParams params) {
      LlamaCppSamplerChain chain = new LlamaCppSamplerChain();
      chain.addSampler(newSamplerPenalties(params));
      if (params.temp() > 0.0F) {
         chain.addSampler(newSamplerTopK(params.top_k()));
         long min_keep = params.min_keep();
         chain.addSampler(newSamplerTypicalP(params.typ_p(), min_keep));
         chain.addSampler(newSamplerTopP(params.top_p(), min_keep));
         chain.addSampler(newSamplerMinP(params.min_p(), min_keep));
         chain.addSampler(newSamplerTempExt(params.temp(), params.dynatemp_range(), params.dynatemp_exponent()));
         chain.addSampler(newSamplerDist());
      } else {
         if (params.n_probs() > 0) {
            chain.addSampler(newSamplerTopK(params.n_probs()));
         }

         chain.addSampler(newSamplerGreedy());
      }

      return chain;
   }

   public static LlamaCppNativeSampler newSamplerGreedy() {
      return new LlamaCppNativeSampler(doInitGreedy());
   }

   public static LlamaCppNativeSampler newSamplerPenalties(int penalty_last_n, float penalty_repeat, float penalty_freq, float penalty_present, boolean penalize_nl, boolean ignore_eos) {
      return new LlamaCppNativeSampler(doInitPenalties(penalty_last_n, penalty_repeat, penalty_freq, penalty_present, penalize_nl, ignore_eos));
   }

   public static LlamaCppNativeSampler newSamplerPenalties(DefaultSamplerChainParams params) {
      return newSamplerPenalties(params.penalty_last_n(), params.penalty_repeat(), params.penalty_freq(), params.penalty_freq(), params.penalize_nl(), params.ignore_eos());
   }

   public static LlamaCppNativeSampler newSamplerTopK(int top_k) {
      return new LlamaCppNativeSampler(doInitTopK(top_k));
   }

   public static LlamaCppNativeSampler newSamplerTopP(float top_p, long min_keep) {
      return new LlamaCppNativeSampler(doInitTopP(top_p, min_keep));
   }

   public static LlamaCppNativeSampler newSamplerMinP(float min_p, long min_keep) {
      return new LlamaCppNativeSampler(doInitMinP(min_p, min_keep));
   }

   public static LlamaCppNativeSampler newSamplerTypicalP(float typ_p, long min_keep) {
      return new LlamaCppNativeSampler(doInitTypicalP(typ_p, min_keep));
   }

   public static LlamaCppNativeSampler newSamplerTempExt(float temp, float dynatemp_range, float dynatemp_exponent) {
      return new LlamaCppNativeSampler(doInitTempExt(temp, dynatemp_range, dynatemp_exponent));
   }

   public static LlamaCppNativeSampler newSamplerTemp(float temp) {
      return new LlamaCppNativeSampler(doInitTemp(temp));
   }

   public static LlamaCppNativeSampler newSamplerDist(int seed) {
      return new LlamaCppNativeSampler(doInitDist(seed));
   }

   public static LlamaCppNativeSampler newSamplerDist() {
      return new LlamaCppNativeSampler(doInitDist());
   }

   public static LlamaCppNativeSampler newSamplerGrammar(LlamaCppModel model, String grammar, String root) {
      return new LlamaCppNativeSampler(doInitGrammar(model, grammar.getBytes(StandardCharsets.UTF_8), root.getBytes(StandardCharsets.UTF_8)));
   }

   public static LlamaCppNativeSampler newJavaSampler(LlamaCppJavaSampler javaSampler) {
      return new LlamaCppNativeSampler(doInitJavaSampler(javaSampler));
   }

   private LlamaCppSamplers() {
   }
}
