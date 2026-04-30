// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.mtmd;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.argeo.jjml.llm.LlamaCppModel;

public class MtmdContext implements LongSupplier, AutoCloseable {
   private final long pointer;

   public MtmdContext(LlamaCppModel model, Path mmprojPath, int threads) {
      this.pointer = doInit(model, filePathToNative(mmprojPath), true, threads);
   }

   private static native long doInit(LlamaCppModel var0, byte[] var1, boolean var2, int var3);

   private native void doDestroy();

   public long getAsLong() {
      return this.pointer;
   }

   public void close() throws Exception {
      this.doDestroy();
   }

   private static byte[] filePathToNative(Path path) {
      return path.toString().getBytes(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")));
   }
}
