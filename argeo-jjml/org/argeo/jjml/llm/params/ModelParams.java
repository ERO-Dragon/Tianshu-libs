// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

public class ModelParams {
   private final int n_gpu_layers;
   private final boolean vocab_only;
   private final boolean use_mmap;
   private final boolean use_mlock;

   ModelParams(int n_gpu_layers, boolean vocab_only, boolean use_mmap, boolean use_mlock) {
      this.n_gpu_layers = n_gpu_layers;
      this.vocab_only = vocab_only;
      this.use_mmap = use_mmap;
      this.use_mlock = use_mlock;
   }

   public ModelParams with(ModelParam key, Object value) {
      Objects.requireNonNull(key);
      Objects.requireNonNull(value);
      return this.with(Collections.singletonMap(key, value.toString()));
   }

   public ModelParams with(Map<ModelParam, String> p) {
      return new ModelParams(Integer.parseInt((String)p.getOrDefault(ModelParam.n_gpu_layers, Integer.toString(this.n_gpu_layers))), Boolean.parseBoolean((String)p.getOrDefault(ModelParam.vocab_only, Boolean.toString(this.vocab_only))), Boolean.parseBoolean((String)p.getOrDefault(ModelParam.use_mmap, Boolean.toString(this.use_mmap))), Boolean.parseBoolean((String)p.getOrDefault(ModelParam.use_mlock, Boolean.toString(this.use_mlock))));
   }

   public int n_gpu_layers() {
      return this.n_gpu_layers;
   }

   public boolean vocab_only() {
      return this.vocab_only;
   }

   public boolean use_mmap() {
      return this.use_mlock;
   }

   public boolean use_mlock() {
      return this.use_mlock;
   }
}
