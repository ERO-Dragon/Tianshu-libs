// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.mtmd;

import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppSamplerChain;

public class MtmdProcessor {
   private final LlamaCppContext context;
   private final LlamaCppSamplerChain chain;
   private final MtmdContext mtmdContext;

   public MtmdProcessor(LlamaCppContext context, LlamaCppSamplerChain chain, MtmdContext mtmdContext) {
      this.context = context;
      this.chain = chain;
      this.mtmdContext = mtmdContext;
   }

   private static native int[] doSingleTurn(long var0, long var2, long var4, byte[] var6, MtmdBitmap[] var7);

   public String transcribe(String prompt, MtmdBitmap[] bitmaps) {
      int[] tokens = doSingleTurn(this.context.getAsLong(), this.chain.getAsLong(), this.mtmdContext.getAsLong(), prompt.getBytes(StandardCharsets.UTF_8), bitmaps);
      String response = this.context.getModel().getVocabulary().deTokenize(IntBuffer.wrap(tokens));
      return response;
   }
}
