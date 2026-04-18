// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.whisper;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import org.argeo.jjml.llm.LlamaCppNative;

public class WhisperCppContext implements LongSupplier, AutoCloseable {
   private final long pointer;

   public WhisperCppContext(Path modelPath) {
      this.pointer = doInit(filePathToNative(modelPath), true, true);
   }

   private static native long doInit(byte[] var0, boolean var1, boolean var2);

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

   static {
      LlamaCppNative.ensureLibrariesLoaded();
      WhisperNative.ensureLibrariesLoaded();
   }
}
