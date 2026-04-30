// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.DoubleConsumer;
import java.util.function.DoublePredicate;
import java.util.function.LongSupplier;
import org.argeo.jjml.llm.params.ModelParam;
import org.argeo.jjml.llm.params.ModelParams;
import org.argeo.jjml.llm.util.InstructRole;

public class LlamaCppModel implements LongSupplier, AutoCloseable {
   private static final ModelParams DEFAULT_MODEL_PARAMS_NATIVE = LlamaCppBackend.newModelParams();
   private final long pointer;
   private final LlamaCppVocabulary vocabulary;
   private final Path localPath;
   private final ModelParams initParams;
   private boolean destroyed = false;
   private final int vocabularySize;
   private final int contextTrainingSize;
   private final int embeddingSize;
   private final int layerCount;
   private final Map<String, String> metadata;
   private final String description;
   private final long modelSize;
   private final int endOfGenerationToken;
   private String chatTemplate = null;

   LlamaCppModel(long pointer, Path localPath, ModelParams initParams) {
      this.pointer = pointer;
      this.vocabulary = new LlamaCppVocabulary(this);
      this.localPath = localPath;
      this.initParams = initParams;
      this.vocabularySize = this.doGetVocabularySize();
      this.contextTrainingSize = this.doGetContextTrainingSize();
      this.embeddingSize = this.doGetEmbeddingSize();
      this.layerCount = this.doGetLayerCount();
      byte[][] keys = this.doGetMetadataKeys();
      byte[][] values = this.doGetMetadataValues();
      if (keys.length != values.length) {
         throw new IllegalStateException("Metadata keys and values don't have the same size");
      } else {
         LinkedHashMap<String, String> map = new LinkedHashMap();

         for(int i = 0; i < keys.length; ++i) {
            map.put(new String(keys[i], StandardCharsets.UTF_8), new String(values[i], StandardCharsets.UTF_8));
         }

         this.metadata = Collections.unmodifiableMap(map);
         if (this.metadata.containsKey("tokenizer.chat_template")) {
            this.chatTemplate = (String)this.metadata.get("tokenizer.chat_template");
         }

         this.description = new String(this.doGetDescription(), StandardCharsets.UTF_8);
         this.modelSize = this.doGetModelSize();
         this.endOfGenerationToken = this.doGetEndOfGenerationToken();
      }
   }

   private static native long doInit(String var0, ModelParams var1, DoublePredicate var2);

   private native void doDestroy();

   private native int doGetVocabularySize();

   private native int doGetContextTrainingSize();

   private native int doGetEmbeddingSize();

   private native int doGetLayerCount();

   private native byte[][] doGetMetadataKeys();

   private native byte[][] doGetMetadataValues();

   private native byte[] doGetDescription();

   private native long doGetModelSize();

   private native int doGetEndOfGenerationToken();

   public String formatChatMessages(LlamaCppChatMessage... messages) {
      return this.formatChatMessages(Arrays.asList(messages));
   }

   public String formatChatMessages(List<LlamaCppChatMessage> messages) {
      return LLamaCppNativeChatFormatter.formatChatMessages(messages, (message) -> message.getRole().equals(InstructRole.USER.get()), this.chatTemplate);
   }

   public void close() throws RuntimeException {
      this.checkDestroyed();
      this.doDestroy();
      this.destroyed = true;
   }

   private void checkDestroyed() {
      if (this.destroyed) {
         throw new IllegalStateException("Model #" + this.pointer + " was already destroyed");
      }
   }

   public long getAsLong() {
      this.checkDestroyed();
      return this.pointer;
   }

   public Path getLocalPath() {
      return this.localPath;
   }

   public ModelParams getInitParams() {
      return this.initParams;
   }

   public LlamaCppVocabulary getVocabulary() {
      return this.vocabulary;
   }

   public int getVocabularySize() {
      return this.vocabularySize;
   }

   public int getContextTrainingSize() {
      return this.contextTrainingSize;
   }

   public int getEmbeddingSize() {
      return this.embeddingSize;
   }

   public int getLayerCount() {
      return this.layerCount;
   }

   public Map<String, String> getMetadata() {
      return this.metadata;
   }

   public String getDescription() {
      return this.description;
   }

   public long getModelSize() {
      return this.modelSize;
   }

   public int getEndOfGenerationToken() {
      return this.endOfGenerationToken;
   }

   public static LlamaCppModel load(Path localPath) throws IOException {
      return load(localPath, defaultModelParams());
   }

   public static ModelParams defaultModelParams() {
      ModelParams res = DEFAULT_MODEL_PARAMS_NATIVE;
      res = res.with(ModelParam.n_gpu_layers, 0);

      for(ModelParam param : ModelParam.values()) {
         String sysProp = System.getProperty(param.asSystemProperty());
         if (sysProp != null) {
            res = res.with(param, sysProp);
         }
      }

      return res;
   }

   public static LlamaCppModel load(Path localPath, ModelParams initParams) throws IOException {
      Future<LlamaCppModel> future = loadAsync(localPath, initParams, (DoubleConsumer)null, (Executor)null);

      try {
         return (LlamaCppModel)future.get();
      } catch (ExecutionException | InterruptedException e) {
         throw new IOException("Cannot load model from " + String.valueOf(localPath), e);
      }
   }

   public static Future<LlamaCppModel> loadAsync(Path localPath, ModelParams initParams, DoubleConsumer progressCallback, Executor executor) throws IOException {
      Objects.requireNonNull(initParams);
      if (!Files.exists(localPath, new LinkOption[0])) {
         throw new FileNotFoundException("Model path " + String.valueOf(localPath) + " does not exist.");
      } else {
         FutureTask<LlamaCppModel> future = new FutureTask(() -> {
            checkInitParams(initParams);
            long pointer = doInit(localPath.toString(), initParams, (progress) -> {
               if (progressCallback != null) {
                  progressCallback.accept(progress);
               }

               return !Thread.interrupted();
            });
            LlamaCppModel model = new LlamaCppModel(pointer, localPath, initParams);
            return model;
         });
         if (executor == null) {
            Thread loadingThread = new Thread(future, "Load model " + String.valueOf(localPath));
            loadingThread.setDaemon(true);
            loadingThread.start();
         } else {
            executor.execute(future);
         }

         return future;
      }
   }

   private static void checkInitParams(ModelParams initParams) {
   }
}
