// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.argeo.jjml.ggml.GgmlBackend;

public class LlamaCppNative {
   public static final String SYSTEM_PROPERTY_LIBPATH_GGML = "jjml.libpath.ggml";
   public static final String SYSTEM_PROPERTY_LIBPATH_LLAMACPP = "jjml.libpath.llamacpp";
   public static final String SYSTEM_PROPERTY_LIBPATH_JJML_LLM = "jjml.libpath.jjml.llm";
   public static final String SYSTEM_PROPERTY_LIBPATH_JJML_GGML = "jjml.libpath.jjml.ggml";
   public static final String ENV_GGML_CUDA_ENABLE_UNIFIED_MEMORY = "GGML_CUDA_ENABLE_UNIFIED_MEMORY";
   private static final String JJML_GGML_LIBRARY_NAME = "Java_org_argeo_jjml_ggml";
   private static final String JJML_LAMA_LIBRARY_NAME = "Java_org_argeo_jjml_llm";
   private static boolean librariesLoaded = false;
   private static Path ggmlLibraryPath;
   private static Path llamaLibraryPath;
   private static Path jjmlLlmLibraryPath;
   private static Path jjmlGgmlLibraryPath;

   public static void ensureLibrariesLoaded() {
      if (!librariesLoaded) {
         loadLibraries();
      }
   }

   static void loadLibraries() {
      checkLibrariesNotLoaded();
      Optional.ofNullable(System.getProperty("jjml.libpath.ggml")).ifPresent((path) -> {
         ggmlLibraryPath = Paths.get(path);
         if (!Files.exists(ggmlLibraryPath, new LinkOption[0])) {
            throw new IllegalArgumentException("jjml.libpath.ggml " + String.valueOf(ggmlLibraryPath) + " does not exist");
         }
      });
      Optional.ofNullable(System.getProperty("jjml.libpath.llamacpp")).ifPresent((path) -> {
         llamaLibraryPath = Paths.get(path);
         if (!Files.exists(llamaLibraryPath, new LinkOption[0])) {
            throw new IllegalArgumentException("jjml.libpath.llamacpp " + String.valueOf(llamaLibraryPath) + " does not exist");
         }
      });
      Optional.ofNullable(System.getProperty("jjml.libpath.jjml.llm")).ifPresent((path) -> {
         jjmlLlmLibraryPath = Paths.get(path);
         if (!Files.exists(jjmlLlmLibraryPath, new LinkOption[0])) {
            throw new IllegalArgumentException("jjml.libpath.jjml.llm " + String.valueOf(jjmlLlmLibraryPath) + " does not exist");
         }
      });
      Optional.ofNullable(System.getProperty("jjml.libpath.jjml.ggml")).ifPresent((path) -> {
         jjmlGgmlLibraryPath = Paths.get(path);
         if (!Files.exists(jjmlLlmLibraryPath, new LinkOption[0])) {
            throw new IllegalArgumentException("jjml.libpath.jjml.ggml " + String.valueOf(jjmlLlmLibraryPath) + " does not exist");
         }
      });
      if (ggmlLibraryPath != null) {
         System.load(ggmlLibraryPath.toAbsolutePath().toString());
      }

      if (llamaLibraryPath != null) {
         System.load(llamaLibraryPath.toAbsolutePath().toString());
      }

      if (jjmlGgmlLibraryPath != null) {
         System.load(jjmlGgmlLibraryPath.toAbsolutePath().toString());
      } else {
         System.loadLibrary("Java_org_argeo_jjml_ggml");
      }

      GgmlBackend.loadAllBackends();
      if (jjmlLlmLibraryPath != null) {
         System.load(jjmlLlmLibraryPath.toAbsolutePath().toString());
      } else {
         System.loadLibrary("Java_org_argeo_jjml_llm");
      }

      librariesLoaded = true;
   }

   public static void setJjmlLlamaLibraryPath(Path jjmlLlamaLibraryPath) {
      checkLibrariesNotLoaded();
      jjmlLlmLibraryPath = jjmlLlamaLibraryPath;
   }

   public static void setLlamaLibraryPath(Path llamaLibraryPath) {
      checkLibrariesNotLoaded();
      LlamaCppNative.llamaLibraryPath = llamaLibraryPath;
   }

   public static void setGgmlLibraryPath(Path ggmlLibraryPath) {
      checkLibrariesNotLoaded();
      LlamaCppNative.ggmlLibraryPath = ggmlLibraryPath;
   }

   private static void checkLibrariesNotLoaded() {
      if (librariesLoaded) {
         throw new IllegalStateException("Shared libraries are already loaded.");
      }
   }

   private LlamaCppNative() {
   }
}
