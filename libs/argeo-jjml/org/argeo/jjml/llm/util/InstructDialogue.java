// Source code is decompiled from a .class file using FernFlower decompiler (from Intellij IDEA).
package org.argeo.jjml.llm.util;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppInstructProcessor;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.LlamaCppSamplerChain;
import org.argeo.jjml.llm.LlamaCppSamplers;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ContextParams;
import org.argeo.jjml.llm.params.DefaultSamplerChainParams;

public class InstructDialogue implements AutoCloseable, Function<String, String>, Consumer<String> {
   private final LlamaCppContext context;
   private final LlamaCppInstructProcessor processor;

   public InstructDialogue(LlamaCppModel model, int contextSize, int parallelism, String systemPrompt) {
      this(model, contextSize, parallelism, systemPrompt, 0.0F);
   }

   public InstructDialogue(LlamaCppModel model, int contextSize, int parallelism, Path stateFile) throws IOException {
      this(model, contextSize, parallelism, stateFile, 0.0F);
   }

   public InstructDialogue(LlamaCppModel model, int contextSize, int parallelism, String systemPrompt, float temperature) {
      this(model, contextSize, parallelism, temperature);
      this.processor.write(this.getSystemRole(), systemPrompt);
   }

   public InstructDialogue(LlamaCppModel model, int contextSize, int parallelism, Path stateFile, float temperature) throws IOException {
      this(model, contextSize, parallelism, temperature);
      this.processor.loadStateFile(stateFile);
   }

   protected InstructDialogue(LlamaCppModel model, int contextSize, int parallelism, float temperature) {
      ContextParams contextParams = this.newContextParams().with(ContextParam.n_ctx, contextSize).with(ContextParam.n_threads, parallelism);
      this.context = new LlamaCppContext(model, contextParams);
      LlamaCppSamplerChain samplerChain = this.newSamplerChain(this.context, temperature);
      this.processor = new LlamaCppInstructProcessor(this.context, samplerChain);
   }

   public String apply(String message) {
      this.processor.write(this.getInputRole(), message);
      StringWriter sw = new StringWriter();

      try {
         this.processor.readMessage(sw);
      } catch (IOException e) {
         throw new UncheckedIOException("Cannot read from LLM context", e);
      }

      return sw.toString();
   }

   public void accept(String message) {
      this.processor.write(this.getInputRole(), message);
   }

   public void close() throws IOException {
      this.context.close();
   }

   public void saveStateFile(Path path) throws IOException {
      this.processor.saveStateFile(path);
   }

   protected ContextParams newContextParams() {
      return LlamaCppContext.defaultContextParams();
   }

   protected LlamaCppSamplerChain newSamplerChain(LlamaCppContext context, float temperature) {
      return LlamaCppSamplers.newDefaultSampler(new DefaultSamplerChainParams(temperature));
   }

   protected Supplier<String> getSystemRole() {
      return InstructRole.SYSTEM;
   }

   protected Supplier<String> getInputRole() {
      return InstructRole.USER;
   }
}
