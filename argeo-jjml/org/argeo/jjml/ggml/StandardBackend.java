// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.ggml;

public enum StandardBackend {
   cpu,
   vulkan,
   cuda,
   hip,
   blas,
   rpc,
   cann,
   metal,
   sycl,
   opencl,
   musa;

   private StandardBackend() {
   }
}
