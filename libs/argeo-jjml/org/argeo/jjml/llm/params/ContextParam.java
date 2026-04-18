// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

public enum ContextParam {
   n_ctx,
   n_batch,
   n_ubatch,
   n_seq_max,
   n_threads,
   n_threads_batch,
   pooling_type,
   type_k,
   type_v,
   embeddings,
   offload_kqv,
   flash_attn,
   kv_unified;

   static final String SYSTEM_PROPERTY_CONTEXT_PARAM_PREFIX = "jjml.llm.context.";

   private ContextParam() {
   }

   public String asSystemProperty() {
      return "jjml.llm.context." + this.name();
   }
}
