package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppEmbeddingProcessor;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ModelParam;
import org.argeo.jjml.llm.params.PoolingType;

import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class EmbeddingEngine {

    private final LlamaCppModel model;
    private final int contextSize;
    private final int threadCount;
    private final int gpuLayers;
    private final String device;
    private final String modelAlias;
    private final ExecutorService embeddingExecutor;
    private volatile boolean running = true;

    static {
        NativeLibraryLoader.ensureLoaded();
    }

    private EmbeddingEngine(LlamaCppModel model, int contextSize, int threadCount, int gpuLayers, String device, String modelAlias) {
        this.model = model;
        this.contextSize = contextSize;
        this.threadCount = threadCount;
        this.gpuLayers = gpuLayers;
        this.device = device;
        this.modelAlias = modelAlias;
        this.embeddingExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "embedding-worker");
            t.setDaemon(false);
            return t;
        });
    }

    public static EmbeddingEngine load(String modelPath, int contextSize, int threadCount, int gpuLayers, String modelAlias) throws Exception {
        return load(modelPath, contextSize, threadCount, gpuLayers, null, modelAlias);
    }

    public static EmbeddingEngine load(String modelPath, int contextSize, int threadCount, int gpuLayers, String device, String modelAlias) throws Exception {
        Path mp = Path.of(modelPath);
        if (!Files.exists(mp)) {
            throw new IllegalArgumentException("Embedding model file not found: " + modelPath);
        }
        System.out.println("[EmbeddingEngine] Loading model: " + modelPath);
        System.out.println("[EmbeddingEngine] GPU layers: " + gpuLayers);
        if (device != null) System.out.println("[EmbeddingEngine] Device: " + device);
        var modelParams = LlamaCppModel.defaultModelParams()
                .with(ModelParam.n_gpu_layers, gpuLayers);
        if (device != null) modelParams = modelParams.with(ModelParam.device, device);
        LlamaCppModel model = LlamaCppModel.loadAsync(mp, modelParams, progress -> {
            if (progress > 0) System.out.print("\r[EmbeddingEngine] Loading: " + (int) (progress * 100) + "%");
        }, null).get();
        System.out.println("\n[EmbeddingEngine] Model loaded: " + model.getDescription());
        System.out.println("[EmbeddingEngine] Embedding size: " + model.getEmbeddingSize());
        return new EmbeddingEngine(model, contextSize, threadCount, gpuLayers, device, modelAlias);
    }

    public float[] embed(String text) throws Exception {
        float[][] vectors = embed(List.of(text));
        if (vectors.length == 0) {
            throw new IllegalStateException("Embedding model returned no vectors");
        }
        return vectors[0];
    }

    public float[][] embed(List<String> texts) throws Exception {
        if (!running) throw new IllegalStateException("Embedding engine is shutting down");
        if (texts == null || texts.isEmpty()) return new float[0][];
        return embeddingExecutor.submit(() -> {
            try (LlamaCppContext context = createEmbeddingContext()) {
                LlamaCppEmbeddingProcessor processor = new LlamaCppEmbeddingProcessor(context);
                return processor.processEmbeddings(texts);
            }
        }).get();
    }

    private LlamaCppContext createEmbeddingContext() {
        var ctxParams = LlamaCppContext.defaultContextParams()
                .with(ContextParam.n_ctx, contextSize)
                .with(ContextParam.n_threads, threadCount)
                .with(ContextParam.embeddings, true)
                .with(ContextParam.pooling_type, PoolingType.LLAMA_POOLING_TYPE_MEAN);
        return new LlamaCppContext(model, ctxParams);
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        System.out.println("[EmbeddingEngine] Shutting down...");
        embeddingExecutor.shutdown();
        boolean terminated = false;
        try {
            terminated = embeddingExecutor.awaitTermination(30, TimeUnit.SECONDS);
            if (!terminated) {
                embeddingExecutor.shutdownNow();
                terminated = embeddingExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            embeddingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (terminated) {
            try { model.close(); } catch (Exception ignored) {}
            System.out.println("[EmbeddingEngine] Shutdown complete.");
        } else {
            System.err.println("[EmbeddingEngine] Worker did not stop; model close skipped to avoid native use-after-close.");
        }
    }

    public boolean isModelLoaded() { return model != null; }
    public String getModelAlias() { return modelAlias; }
    public int getContextSize() { return contextSize; }
    public int getThreadCount() { return threadCount; }
    public int getGpuLayers() { return gpuLayers; }
    public String getDevice() { return device; }
    public int getEmbeddingSize() { return model.getEmbeddingSize(); }
    public int getLayerCount() { return model.getLayerCount(); }
    public long getModelSize() { return model.getModelSize(); }
}
