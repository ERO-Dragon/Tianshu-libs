// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.mtmd;

import java.util.function.LongSupplier;

public abstract class MtmdBitmap implements LongSupplier, AutoCloseable {
   private final long pointer;

   protected MtmdBitmap(long pointer) {
      this.pointer = pointer;
   }

   public abstract MtmdInputChunkType getType();

   private native void doDestroy();

   public long getAsLong() {
      return this.pointer;
   }

   public void close() throws Exception {
      this.doDestroy();
   }
}
