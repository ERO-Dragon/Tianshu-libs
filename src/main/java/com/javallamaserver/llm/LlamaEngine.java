package com.javallamaserver.llm;

import org.argeo.jjml.llm.LlamaCppBackend;
import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.LlamaCppNative;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ModelParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

public class LlamaEngine {

    private static LlamaEngine instance;

    private final LlamaCppModel model;
    private final int contextSize;
    private final int threadCount;
    private final int gpuLayers;
    private final String modelAlias;

    private final LinkedBlockingQueue<InferenceTask> taskQueue = new LinkedBlockingQueue<>();
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "llama-inference-worker");
        t.setDaemon(false);
        return t;
    });
    private volatile boolean running = true;

    static {
        LlamaCppNative.ensureLibrariesLoaded();
    }

    private LlamaEngine(LlamaCppModel model, int contextSize, int threadCount, int gpuLayers, String modelAlias) {
        this.model = model;
        this.contextSize = contextSize;
        this.threadCount = threadCount;
        this.gpuLayers = gpuLayers;
        this.modelAlias = modelAlias;
    }

    public static synchronized LlamaEngine initialize(String modelPath, int contextSize, int threadCount,
                                                      int gpuLayers, String modelAlias) throws Exception {
        if (instance != null) {
            throw new IllegalStateException("Engine already initialized");
        }
        Path mp = Path.of(modelPath);
        if (!Files.exists(mp)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        System.out.println("[LlamaEngine] Loading model: " + modelPath);
        var modelParams = LlamaCppModel.defaultModelParams()
                .with(ModelParam.n_gpu_layers, gpuLayers);
        LlamaCppModel model = LlamaCppModel.loadAsync(mp, modelParams, progress -> {
            if (progress > 0) System.out.print("\r[LlamaEngine] Loading: " + (int) (progress * 100) + "%");
        }, null).get();
        System.out.println("\n[LlamaEngine] Model loaded: " + model.getDescription());

        instance = new LlamaEngine(model, contextSize, threadCount, gpuLayers, modelAlias);
        instance.startWorker();
        Runtime.getRuntime().addShutdownHook(new Thread(instance::shutdown, "llama-shutdown-hook"));
        return instance;
    }

    public static synchronized LlamaEngine getInstance() {
        if (instance == null) throw new IllegalStateException("Engine not initialized");
        return instance;
    }

    private void startWorker() {
        inferenceExecutor.submit(new TaskExecutor(this, taskQueue));
        System.out.println("[LlamaEngine] Inference worker started.");
    }

    public void submitTask(InferenceTask task) {
        if (!running) throw new IllegalStateException("Engine is shutting down");
        taskQueue.add(task);
    }

    LlamaCppContext createContext() {
        var ctxParams = LlamaCppContext.defaultContextParams()
                .with(ContextParam.n_ctx, contextSize)
                .with(ContextParam.n_threads, threadCount);
        return new LlamaCppContext(model, ctxParams);
    }

    LlamaCppModel getModel() { return model; }
    public String getModelAlias() { return modelAlias; }
    public int getContextSize() { return contextSize; }
    public boolean isRunning() { return running; }

    public void shutdown() {
        if (!running) return;
        running = false;
        System.out.println("[LlamaEngine] Shutting down...");
        inferenceExecutor.shutdown();
        try {
            if (!inferenceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                inferenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            inferenceExecutor.shutdownNow();
        }
        try { model.close(); } catch (Exception ignored) {}
        LlamaCppBackend.destroy();
        System.out.println("[LlamaEngine] Shutdown complete.");
    }

    public int getQueueSize() { return taskQueue.size(); }

    public boolean isModelLoaded() { return model != null; }

    public int getVocabularySize() { return model.getVocabularySize(); }
    public int getEmbeddingSize() { return model.getEmbeddingSize(); }
    public int getLayerCount() { return model.getLayerCount(); }
    public long getModelSize() { return model.getModelSize(); }
}
