// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.mtmd;

import java.nio.charset.StandardCharsets;

public class MtmdBackend {
   private static final String DEFAULT_MARKER;

   private static native byte[] doGetDefaultMarker();

   public static String getDefaultMarker() {
      return DEFAULT_MARKER;
   }

   private MtmdBackend() {
   }

   static {
      MtmdNative.ensureLibrariesLoaded();
      DEFAULT_MARKER = new String(doGetDefaultMarker(), StandardCharsets.UTF_8);
   }
}
