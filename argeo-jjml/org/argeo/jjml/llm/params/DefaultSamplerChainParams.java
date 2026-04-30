// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

public class DefaultSamplerChainParams {
   float temp;
   int n_probs;
   long min_keep;
   int top_k;
   float top_p;
   float min_p;
   float tfs_z;
   float typ_p;
   float dynatemp_range;
   float dynatemp_exponent;
   int penalty_last_n;
   float penalty_repeat;
   float penalty_freq;
   float penalty_present;
   boolean penalize_nl;
   boolean ignore_eos;

   public DefaultSamplerChainParams(float temp, int n_probs, long min_keep, int top_k, float top_p, float min_p, float tfs_z, float typ_p, float dynatemp_range, float dynatemp_exponent, int penalty_last_n, float penalty_repeat, float penalty_freq, float penalty_present, boolean penalize_nl, boolean ignore_eos) {
      this.temp = temp;
      this.n_probs = n_probs;
      this.min_keep = min_keep;
      this.top_k = top_k;
      this.top_p = top_p;
      this.min_p = min_p;
      this.tfs_z = tfs_z;
      this.typ_p = typ_p;
      this.dynatemp_range = dynatemp_range;
      this.dynatemp_exponent = dynatemp_exponent;
      this.penalty_last_n = penalty_last_n;
      this.penalty_repeat = penalty_repeat;
      this.penalty_freq = penalty_freq;
      this.penalty_present = penalty_present;
      this.penalize_nl = penalize_nl;
      this.ignore_eos = ignore_eos;
   }

   public DefaultSamplerChainParams() {
      this(0.8F);
   }

   public DefaultSamplerChainParams(float temp) {
      this(temp, 0);
   }

   public DefaultSamplerChainParams(int n_probs) {
      this(0.0F, n_probs);
   }

   public DefaultSamplerChainParams(float temp, int n_probs) {
      this(temp, n_probs, 0L, 40, 0.95F, 0.05F, 1.0F, 1.0F, 0.0F, 1.0F, 64, 1.0F, 0.0F, 0.0F, false, false);
   }

   public float temp() {
      return this.temp;
   }

   public int n_probs() {
      return this.n_probs;
   }

   public long min_keep() {
      return this.min_keep;
   }

   public int top_k() {
      return this.top_k;
   }

   public float top_p() {
      return this.top_p;
   }

   public float min_p() {
      return this.min_p;
   }

   public float tfs_z() {
      return this.tfs_z;
   }

   public float typ_p() {
      return this.typ_p;
   }

   public float dynatemp_range() {
      return this.dynatemp_range;
   }

   public float dynatemp_exponent() {
      return this.dynatemp_exponent;
   }

   public int penalty_last_n() {
      return this.penalty_last_n;
   }

   public float penalty_repeat() {
      return this.penalty_repeat;
   }

   public float penalty_freq() {
      return this.penalty_freq;
   }

   public float penalty_present() {
      return this.penalty_present;
   }

   public boolean penalize_nl() {
      return this.penalize_nl;
   }

   public boolean ignore_eos() {
      return this.ignore_eos;
   }
}
