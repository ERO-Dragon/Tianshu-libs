// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntSupplier;

public class ContextParams {
   private final int n_ctx;
   private final int n_batch;
   private final int n_ubatch;
   private final int n_seq_max;
   private final int n_threads;
   private final int n_threads_batch;
   private final int rope_scaling_type;
   private final int pooling_type;
   private final int attention_type;
   private final float rope_freq_base;
   private final float rope_freq_scale;
   private final float yarn_ext_factor;
   private final float yarn_attn_factor;
   private final float yarn_beta_fast;
   private final float yarn_beta_slow;
   private final int yarn_orig_ctx;
   private final float defrag_thold;
   private final int type_k;
   private final int type_v;
   private final boolean embeddings;
   private final boolean offload_kqv;
   private final boolean flash_attn;
   private final boolean no_perf;
   private final boolean op_offload;
   private final boolean swa_full;
   private final boolean kv_unified;

   ContextParams(int n_ctx, int n_batch, int n_ubatch, int n_seq_max, int n_threads, int n_threads_batch, int rope_scaling_type, int pooling_type, int attention_type, float rope_freq_base, float rope_freq_scale, float yarn_ext_factor, float yarn_attn_factor, float yarn_beta_fast, float yarn_beta_slow, int yarn_orig_ctx, float defrag_thold, int type_k, int type_v, boolean embeddings, boolean offload_kqv, boolean flash_attn, boolean no_perf, boolean op_offload, boolean swa_full, boolean kv_unified) {
      this.n_ctx = n_ctx;
      this.n_batch = n_batch;
      this.n_ubatch = n_ubatch;
      this.n_seq_max = n_seq_max;
      this.n_threads = n_threads;
      this.n_threads_batch = n_threads_batch;
      this.rope_scaling_type = rope_scaling_type;
      this.pooling_type = pooling_type;
      this.attention_type = attention_type;
      this.rope_freq_base = rope_freq_base;
      this.rope_freq_scale = rope_freq_scale;
      this.yarn_ext_factor = yarn_ext_factor;
      this.yarn_attn_factor = yarn_attn_factor;
      this.yarn_beta_fast = yarn_beta_fast;
      this.yarn_beta_slow = yarn_beta_slow;
      this.yarn_orig_ctx = yarn_orig_ctx;
      this.defrag_thold = defrag_thold;
      this.type_k = type_k;
      this.type_v = type_v;
      this.embeddings = embeddings;
      this.offload_kqv = offload_kqv;
      this.flash_attn = flash_attn;
      this.no_perf = no_perf;
      this.op_offload = op_offload;
      this.swa_full = swa_full;
      this.kv_unified = kv_unified;
   }

   public ContextParams with(ContextParam key, Object value) {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      String str;
      if (value instanceof IntSupplier) {
         str = Integer.toString(((IntSupplier)value).getAsInt());
      } else {
         str = value.toString();
      }

      return this.with(Collections.singletonMap(key, str));
   }

   public ContextParams with(Map<ContextParam, String> p) {
      return new ContextParams(Integer.parseInt((String)p.getOrDefault(ContextParam.n_ctx, Integer.toString(this.n_ctx))), Integer.parseInt((String)p.getOrDefault(ContextParam.n_batch, Integer.toString(this.n_batch))), Integer.parseInt((String)p.getOrDefault(ContextParam.n_ubatch, Integer.toString(this.n_ubatch))), Integer.parseInt((String)p.getOrDefault(ContextParam.n_seq_max, Integer.toString(this.n_seq_max))), Integer.parseInt((String)p.getOrDefault(ContextParam.n_threads, Integer.toString(this.n_threads))), Integer.parseInt((String)p.getOrDefault(ContextParam.n_threads_batch, Integer.toString(this.n_threads_batch))), this.rope_scaling_type, Integer.parseInt((String)p.getOrDefault(ContextParam.pooling_type, Integer.toString(this.pooling_type))), this.attention_type, this.rope_freq_base, this.rope_freq_scale, this.yarn_ext_factor, this.yarn_attn_factor, this.yarn_beta_fast, this.yarn_beta_slow, this.yarn_orig_ctx, this.defrag_thold, Integer.parseInt((String)p.getOrDefault(ContextParam.type_k, Integer.toString(this.type_k))), Integer.parseInt((String)p.getOrDefault(ContextParam.type_v, Integer.toString(this.type_v))), Boolean.parseBoolean((String)p.getOrDefault(ContextParam.embeddings, Boolean.toString(this.embeddings))), Boolean.parseBoolean((String)p.getOrDefault(ContextParam.offload_kqv, Boolean.toString(this.offload_kqv))), Boolean.parseBoolean((String)p.getOrDefault(ContextParam.flash_attn, Boolean.toString(this.flash_attn))), this.no_perf, this.op_offload, this.swa_full, Boolean.parseBoolean((String)p.getOrDefault(ContextParam.kv_unified, Boolean.toString(this.kv_unified))));
   }

   public int n_ctx() {
      return this.n_ctx;
   }

   public int n_batch() {
      return this.n_batch;
   }

   public int n_ubatch() {
      return this.n_ubatch;
   }

   public int n_seq_max() {
      return this.n_seq_max;
   }

   public int n_threads() {
      return this.n_threads;
   }

   public int n_threads_batch() {
      return this.n_threads_batch;
   }

   public int rope_scaling_type() {
      return this.rope_scaling_type;
   }

   public int pooling_type() {
      return this.pooling_type;
   }

   public int attention_type() {
      return this.attention_type;
   }

   public float rope_freq_base() {
      return this.rope_freq_base;
   }

   public float rope_freq_scale() {
      return this.rope_freq_scale;
   }

   public float yarn_ext_factor() {
      return this.yarn_ext_factor;
   }

   public float yarn_attn_factor() {
      return this.yarn_attn_factor;
   }

   public float yarn_beta_fast() {
      return this.yarn_beta_fast;
   }

   public float yarn_beta_slow() {
      return this.yarn_beta_slow;
   }

   public int yarn_orig_ctx() {
      return this.yarn_orig_ctx;
   }

   public float defrag_thold() {
      return this.defrag_thold;
   }

   public int type_k() {
      return this.type_k;
   }

   public int type_v() {
      return this.type_v;
   }

   public boolean embeddings() {
      return this.embeddings;
   }

   public boolean offload_kqv() {
      return this.offload_kqv;
   }

   public boolean flash_attn() {
      return this.flash_attn;
   }

   public boolean no_perf() {
      return this.no_perf;
   }

   public boolean op_offload() {
      return this.op_offload;
   }

   public boolean swa_full() {
      return this.swa_full;
   }

   public boolean kv_unified() {
      return this.kv_unified;
   }
}
