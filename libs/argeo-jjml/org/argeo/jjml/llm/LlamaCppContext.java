// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ContextParams;
import org.argeo.jjml.llm.params.PoolingType;

public class LlamaCppContext implements LongSupplier, AutoCloseable {
   private static final ContextParams DEFAULT_CONTEXT_PARAMS_NATIVE = LlamaCppBackend.newContextParams();
   private final long pointer;
   private final LlamaCppModel model;
   private final ContextParams initParams;
   private final PoolingType poolingType;
   private final int contextSize;
   private final int batchSize;
   private final int maxSequenceCount;

   public LlamaCppContext(LlamaCppModel model) {
      this(model, DEFAULT_CONTEXT_PARAMS_NATIVE);
   }

   public LlamaCppContext(LlamaCppModel model, ContextParams initParams) {
      Objects.requireNonNull(model);
      Objects.requireNonNull(initParams);
      if (initParams.embeddings() && initParams.n_ubatch() != initParams.n_batch()) {
         initParams = initParams.with(ContextParam.n_batch, initParams.n_ubatch());
      }

      this.pointer = doInit(model, initParams);
      this.model = model;
      this.initParams = initParams;
      int poolingTypeCode = this.doGetPoolingType();
      this.poolingType = PoolingType.byCode(poolingTypeCode);
      this.contextSize = this.doGetContextSize();
      this.batchSize = this.doGetBatchSize();
      this.maxSequenceCount = this.doGetMaxSequenceCount();
   }

   private static native long doInit(LlamaCppModel var0, ContextParams var1);

   private native void doDestroy();

   private native int doGetPoolingType();

   private native int doGetContextSize();

   private native int doGetBatchSize();

   private native int doGetPhysicalBatchSize();

   private native int doGetMaxSequenceCount();

   private native long doGetStateSize();

   private native byte[] doGetStateDataAsBytes();

   private native int doGetStateData(ByteBuffer var1, int var2);

   private native void doSetStateDataBytes(byte[] var1, int var2, int var3);

   private native void doSetStateData(ByteBuffer var1, int var2, int var3);

   private native void doSaveStateFile(byte[] var1, IntBuffer var2, int var3, int var4);

   private native int doLoadStateFile(byte[] var1, IntBuffer var2, int var3);

   long getStateSize() {
      return this.doGetStateSize();
   }

   void readState(ByteBuffer buf) {
      if (buf.isDirect()) {
         int offset = buf.position();
         int read = this.doGetStateData(buf, offset);
         buf.position(offset + read);
      } else {
         byte[] arr = this.doGetStateDataAsBytes();
         buf.put(arr);
      }

   }

   void writeState(ByteBuffer buf) {
      if (buf.isDirect()) {
         this.doSetStateData(buf, 0, buf.limit());
         buf.position(buf.limit());
      } else {
         byte[] arr = buf.array();
         this.doSetStateDataBytes(arr, 0, arr.length);
      }

   }

   void saveStateFile(Path path, IntBuffer tokens) throws IOException {
      if (!tokens.isDirect()) {
         throw new IllegalArgumentException("Tokens must be in a direct buffer");
      } else if (Files.exists(path, new LinkOption[0]) && !Files.isWritable(path)) {
         throw new IOException("Location " + String.valueOf(path) + " for session file is not writable");
      } else {
         this.doSaveStateFile(filePathToNative(path), tokens, 0, tokens.position());
      }
   }

   int loadStateFile(Path path, IntBuffer tokens) throws IOException {
      if (!Files.exists(path, new LinkOption[0])) {
         throw new FileNotFoundException("Session file " + String.valueOf(path) + " does not exist");
      } else if (!tokens.isDirect()) {
         throw new IllegalArgumentException("Tokens must be in a direct buffer");
      } else {
         int tokenCount = this.doLoadStateFile(filePathToNative(path), tokens, 0);
         tokens.position(tokenCount);
         return tokenCount;
      }
   }

   public void close() throws RuntimeException {
      this.doDestroy();
   }

   public long getAsLong() {
      return this.pointer;
   }

   public LlamaCppModel getModel() {
      return this.model;
   }

   public ContextParams getInitParams() {
      return this.initParams;
   }

   public PoolingType getPoolingType() {
      return this.poolingType;
   }

   public int getContextSize() {
      return this.contextSize;
   }

   public int getBatchSize() {
      return this.batchSize;
   }

   public int getMaxSequenceCount() {
      return this.maxSequenceCount;
   }

   public static ContextParams defaultContextParams() {
      ContextParams res = DEFAULT_CONTEXT_PARAMS_NATIVE;

      for(ContextParam param : ContextParam.values()) {
         String sysProp = System.getProperty(param.asSystemProperty());
         if (sysProp != null) {
            res = res.with(param, sysProp);
         }
      }

      return res;
   }

   private static byte[] filePathToNative(Path path) {
      return path.toString().getBytes(Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8")));
   }
}
