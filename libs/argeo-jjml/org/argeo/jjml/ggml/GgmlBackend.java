// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.ggml;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GgmlBackend {
   private static final String GGML_DL_PREFIX = "ggml-";
   private static final List<GgmlBackend> loadedBackends = new ArrayList();
   private final long pointer;
   private final String name;
   private final Path path;

   public GgmlBackend(long pointer, String name, Path path) {
      this.pointer = pointer;
      this.name = name;
      this.path = path;
   }

   private static native long doLoadBackend(byte[] var0);

   private static native void doLoadAllBackends(byte[] var0);

   public static void loadAllBackends() {
      List<Path> basePaths = new ArrayList();
      String arch = System.getProperty("os.arch");
      String gnuArch;
      if (!"arm64".equals(arch) && !"aarch64".equals(arch)) {
         gnuArch = "x86_64";
      } else {
         gnuArch = "aarch64";
      }

      Path path = Paths.get("/usr/lib/" + gnuArch + "-linux-gnu/ggml/backends0");
      if (Files.exists(path, new LinkOption[0])) {
         basePaths.add(path);
      } else {
         basePaths.add(Paths.get("/usr/libexec/" + gnuArch + "-linux-gnu/ggml"));
      }

      String javaLibraryPath = System.getProperty("java.library.path");
      if (javaLibraryPath != null && !"".equals(javaLibraryPath.trim())) {
         String[] paths = javaLibraryPath.split(File.pathSeparator);

         for(String p : paths) {
            basePaths.add(Paths.get(p));
         }
      }

      javaLibraryPath = System.getenv("LD_LIBRARY_PATH");
      if (javaLibraryPath != null && !"".equals(javaLibraryPath.trim())) {
         String[] paths = javaLibraryPath.split(File.pathSeparator);

         for(String p : paths) {
            basePaths.add(Paths.get(p));
         }
      }

      javaLibraryPath = System.getenv("DYLD_LIBRARY_PATH");
      if (javaLibraryPath != null && !"".equals(javaLibraryPath.trim())) {
         String[] paths = javaLibraryPath.split(File.pathSeparator);

         for(String p : paths) {
            basePaths.add(Paths.get(p));
         }
      }

      Path lastRelevantPath = null;
      Iterator var18 = basePaths.iterator();

      while(true) {
         Path basePath;
         while(true) {
            if (!var18.hasNext()) {
               if (lastRelevantPath != null) {
                  doLoadAllBackends(filePathToNative(lastRelevantPath));
               } else {
                  System.err.println("Could not find ggml backends in any of " + String.valueOf(basePaths));
               }

               return;
            }

            basePath = (Path)var18.next();
            if (Files.exists(basePath, new LinkOption[0])) {
               try {
                  DirectoryStream<Path> ds = Files.newDirectoryStream(basePath, "*ggml-cpu*");

                  label116: {
                     try {
                        Iterator<Path> it = ds.iterator();
                        if (!it.hasNext()) {
                           break label116;
                        }
                     } catch (Throwable var11) {
                        if (ds != null) {
                           try {
                              ds.close();
                           } catch (Throwable var10) {
                              var11.addSuppressed(var10);
                           }
                        }

                        throw var11;
                     }

                     if (ds != null) {
                        ds.close();
                     }
                     break;
                  }

                  if (ds != null) {
                     ds.close();
                  }
               } catch (IOException var12) {
               }
            }
         }

         lastRelevantPath = basePath;
      }
   }

   public String getName() {
      return this.name;
   }

   public Path getPath() {
      return this.path;
   }

   public static void loadBackends(Path basePath) {
      label32:
      for(StandardBackend backendName : StandardBackend.values()) {
         for(GgmlBackend backend : loadedBackends) {
            if (backendName.name().equals(backend.getName())) {
               PrintStream var10000 = System.err;
               String var10001 = backendName.name();
               var10000.println(var10001 + " already loaded from " + String.valueOf(backend.getPath()));
               continue label32;
            }
         }

         String dllName;
         if (File.separatorChar == '\\') {
            dllName = "ggml-" + backendName.name() + ".dll";
         } else {
            dllName = "libggml-" + backendName.name() + ".so";
         }

         Path backendPath = basePath.resolve(dllName);
         if (Files.exists(backendPath, new LinkOption[0])) {
            long pointer = doLoadBackend(filePathToNative(backendPath));
            if (pointer > 0L) {
               GgmlBackend backend = new GgmlBackend(pointer, backendName.name(), backendPath);
               loadedBackends.add(backend);
               PrintStream var12 = System.out;
               String var13 = backendName.name();
               var12.println("Loaded backend " + var13 + " from " + String.valueOf(backend.getPath()));
            }
         }
      }

   }

   private static byte[] filePathToNative(Path path) {
      return path.toString().getBytes(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")));
   }
}
