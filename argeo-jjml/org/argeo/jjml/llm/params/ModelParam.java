// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.params;

public enum ModelParam {
   n_gpu_layers,
   vocab_only,
   use_mmap,
   use_mlock;

   static final String SYSTEM_PROPERTY_MODEL_PARAM_PREFIX = "jjml.llm.model.";

   private ModelParam() {
   }

   public String asSystemProperty() {
      return "jjml.llm.model." + this.name();
   }
}
