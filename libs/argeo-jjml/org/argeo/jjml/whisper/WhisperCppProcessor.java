// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.whisper;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;

public class WhisperCppProcessor {
   public static final float WHISPER_SAMPLE_RATE = 16000.0F;
   private final WhisperCppContext context;

   public WhisperCppProcessor(WhisperCppContext context) {
      this.context = context;
   }

   private static native byte[] doFull(long var0, FloatBuffer var2, int var3, int var4);

   public String transcribe(FloatBuffer floatBuf) throws IOException {
      byte[] utf8 = doFull(this.context.getAsLong(), floatBuf, 0, floatBuf.limit());
      return new String(utf8, StandardCharsets.UTF_8);
   }
}
