// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.function.DoubleConsumer;

public class SimpleModelDownload {
   private int bufferSize;
   private final Path modelsBase;

   public SimpleModelDownload(Path modelsBase) {
      this.bufferSize = 4194304;
      this.modelsBase = modelsBase;
   }

   public SimpleModelDownload() {
      this(getDefaultModelsBase());
   }

   public URL getRemoteUrl(String hfRepo, String quantization) {
      URI uri = this.getRemoteUri(hfRepo, quantization);

      try {
         return uri.toURL();
      } catch (MalformedURLException e) {
         throw new IllegalArgumentException("Malformed download URL: " + String.valueOf(uri), e);
      }
   }

   public URI getRemoteUri(String hfRepo, String quantization) {
      return URI.create("https://huggingface.co/" + hfRepo + "/resolve/main/" + this.getRemoteFileName(hfRepo, quantization) + "?download=true");
   }

   public String getRemoteFileName(String hfRepo, String quantization) {
      String fileName = hfRepo.split("/")[1].replace("-GGUF", "-" + quantization + ".gguf");
      return fileName;
   }

   public String getLocalFileName(String hfRepo, String quantization) {
      String fileName = hfRepo.split("/")[1].replace("-GGUF", "-" + quantization + ".gguf");
      String var10000 = hfRepo.replace("/", "_");
      String localFileName = var10000 + "_" + fileName;
      return localFileName;
   }

   public Path getLocalFile(String hfRepo, String quantization) {
      return this.modelsBase.resolve(this.getLocalFileName(hfRepo, quantization));
   }

   public Path getOrDownloadModel(String hfRepoArg, DoubleConsumer progressCallback) throws IOException {
      return hfRepoArg.contains(":") ? this.getOrDownloadModel(hfRepoArg.split(":")[0], hfRepoArg.split(":")[1], progressCallback) : this.getOrDownloadModel(hfRepoArg, "Q4_K_M", progressCallback);
   }

   public Path getOrDownloadModel(String hfRepo, String quantization, DoubleConsumer progressCallback) throws IOException {
      Path localFile = this.getLocalFile(hfRepo, quantization);
      String currentEtag = null;
      if (Files.exists(localFile, new LinkOption[0])) {
         try {
            byte[] buf = null;
            if (buf != null) {
               currentEtag = new String(buf, StandardCharsets.US_ASCII);
            }
         } catch (Exception var20) {
         }

         if (currentEtag == null) {
            return localFile;
         }
      }

      Files.createDirectories(localFile.getParent());
      URL url = this.getRemoteUrl(hfRepo, quantization);
      HttpURLConnection urlConnection = (HttpURLConnection)url.openConnection();
      if (currentEtag != null) {
         urlConnection.setRequestProperty("If-None-Match", currentEtag);
      }

      urlConnection.connect();
      InputStream in = urlConnection.getInputStream();

      label96: {
         Path var13;
         try {
            int responseCode = urlConnection.getResponseCode();
            double contentLength = (double)urlConnection.getContentLengthLong();
            String etag = urlConnection.getHeaderField("etag");
            if (responseCode != 304 && !etag.equals(currentEtag)) {
               OutputStream out = Files.newOutputStream(localFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

               try {
                  byte[] buf = new byte[this.bufferSize];
                  double downloaded = (double)0.0F;

                  int len;
                  while((len = in.read(buf)) != -1) {
                     out.write(buf, 0, len);
                     downloaded += (double)len;
                     if (progressCallback != null) {
                        progressCallback.accept(downloaded / contentLength);
                     }
                  }
               } catch (Throwable var21) {
                  if (out != null) {
                     try {
                        out.close();
                     } catch (Throwable var19) {
                        var21.addSuppressed(var19);
                     }
                  }

                  throw var21;
               }

               if (out != null) {
                  out.close();
               }

               if (etag != null) {
               }
               break label96;
            }

            var13 = localFile;
         } catch (Throwable var22) {
            if (in != null) {
               try {
                  in.close();
               } catch (Throwable var18) {
                  var22.addSuppressed(var18);
               }
            }

            throw var22;
         }

         if (in != null) {
            in.close();
         }

         return var13;
      }

      if (in != null) {
         in.close();
      }

      return localFile;
   }

   public static Path getDefaultModelsBase() {
      String os = System.getProperty("os.name").toLowerCase();
      Path defaultModelsBase;
      if (os.contains("win")) {
         defaultModelsBase = Paths.get(System.getProperty("user.home"), "AppData", "Local", "llama.cpp");
      } else if (!os.contains("mac") && !os.contains("darwin")) {
         defaultModelsBase = Paths.get(System.getProperty("user.home"), ".cache", "llama.cpp");
      } else {
         defaultModelsBase = Paths.get(System.getProperty("user.home"), "Library", "Caches", "llama.cpp");
      }

      return defaultModelsBase;
   }
}
