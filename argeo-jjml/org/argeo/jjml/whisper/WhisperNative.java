// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.whisper;

import org.argeo.jjml.llm.LlamaCppNative;

public class WhisperNative {
   private static final String JJML_WHISPER_LIBRARY_NAME = "Java_org_argeo_jjml_whisper";
   private static boolean librariesLoaded = false;

   public static boolean isAvailable() {
      try {
         ensureLibrariesLoaded();
         return true;
      } catch (UnsatisfiedLinkError var1) {
         return false;
      }
   }

   public static synchronized void ensureLibrariesLoaded() {
      if (!librariesLoaded) {
         LlamaCppNative.ensureLibrariesLoaded();
         loadLibraries();
      }
   }

   static synchronized void loadLibraries() {
      checkLibrariesNotLoaded();
      System.loadLibrary("Java_org_argeo_jjml_whisper");
      librariesLoaded = true;
   }

   private static void checkLibrariesNotLoaded() {
      if (librariesLoaded) {
         throw new IllegalStateException("Shared libraries are already loaded.");
      }
   }

   private WhisperNative() {
   }
}
