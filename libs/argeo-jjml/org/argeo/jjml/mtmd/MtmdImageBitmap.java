// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.mtmd;

import java.nio.ByteBuffer;

public class MtmdImageBitmap extends MtmdBitmap {
   protected MtmdImageBitmap(ByteBuffer rgb, int width, int height) {
      super(doInit(rgb, width, height));
   }

   protected MtmdImageBitmap(byte[] rgb, int offset, int width, int height) {
      super(doInitFromBytes(rgb, offset, width, height));
   }

   private static native long doInit(ByteBuffer var0, int var1, int var2);

   private static native long doInitFromBytes(byte[] var0, int var1, int var2, int var3);

   public MtmdInputChunkType getType() {
      return MtmdInputChunkType.IMAGE;
   }

   public void close() throws Exception {
      super.close();
   }
}
