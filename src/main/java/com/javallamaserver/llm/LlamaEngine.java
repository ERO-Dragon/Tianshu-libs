package com.javallamaserver.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.LlamaCppNative;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ModelParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class LlamaEngine {

    private final String engineName;
    private final LlamaCppModel model;
    private final int contextSize;
    private final int threadCount;
    private final int gpuLayers;
    private final String modelAlias;
    private final String modelProfile;

    private final LinkedBlockingQueue<InferenceTask> taskQueue;
    private final ExecutorService inferenceExecutor;
    private final int maxQueueSize;
    private volatile boolean running = true;

    static {
        LlamaCppNative.ensureLibrariesLoaded();
    }

    private LlamaEngine(String engineName, LlamaCppModel model, int contextSize, int threadCount, int gpuLayers, String modelAlias, String modelProfile, int maxQueueSize) {
        this.engineName = engineName;
        this.model = model;
        this.contextSize = contextSize;
        this.threadCount = threadCount;
        this.gpuLayers = gpuLayers;
        this.modelAlias = modelAlias;
        this.modelProfile = modelProfile;
        this.maxQueueSize = Math.max(1, maxQueueSize);
        this.taskQueue = new LinkedBlockingQueue<>(this.maxQueueSize);
        this.inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, engineName + "-inference-worker");
            t.setDaemon(false);
            return t;
        });
    }

    public static LlamaEngine loadChatEngine(String modelPath, int contextSize, int threadCount,
                                             int gpuLayers, String modelAlias, String modelProfile) throws Exception {
        return loadChatEngine(modelPath, contextSize, threadCount, gpuLayers, modelAlias, modelProfile, 4);
    }

    public static LlamaEngine loadChatEngine(String modelPath, int contextSize, int threadCount,
                                             int gpuLayers, String modelAlias, String modelProfile, int maxQueueSize) throws Exception {
        LlamaCppModel model = loadModel("chat", modelPath, gpuLayers);
        String resolvedModelProfile = resolveModelProfile(model, modelPath, modelProfile);
        System.out.println("[LlamaEngine:chat] Model profile: " + resolvedModelProfile);
        LlamaEngine engine = new LlamaEngine("chat", model, contextSize, threadCount, gpuLayers, modelAlias, resolvedModelProfile, maxQueueSize);
        engine.startWorker();
        return engine;
    }

    private static LlamaCppModel loadModel(String engineName, String modelPath, int gpuLayers) throws Exception {
        Path mp = Path.of(modelPath);
        if (!Files.exists(mp)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        System.out.println("[LlamaEngine:" + engineName + "] Loading model: " + modelPath);
        System.out.println("[LlamaEngine:" + engineName + "] GPU layers: " + gpuLayers);
        var modelParams = LlamaCppModel.defaultModelParams()
                .with(ModelParam.n_gpu_layers, gpuLayers);
        LlamaCppModel model = LlamaCppModel.loadAsync(mp, modelParams, progress -> {
            if (progress > 0) System.out.print("\r[LlamaEngine:" + engineName + "] Loading: " + (int) (progress * 100) + "%");
        }, null).get();
        System.out.println("\n[LlamaEngine:" + engineName + "] Model loaded: " + model.getDescription());
        return model;
    }

    private void startWorker() {
        inferenceExecutor.submit(new TaskExecutor(this, taskQueue));
        System.out.println("[LlamaEngine:" + engineName + "] Inference worker started.");
    }

    private static String normalizeModelProfile(String modelProfile) {
        if (modelProfile == null || modelProfile.isBlank()) return "auto";
        return modelProfile.trim().toLowerCase();
    }

    private static String resolveModelProfile(LlamaCppModel model, String modelPath, String requestedProfile) {
        String normalized = normalizeModelProfile(requestedProfile);
        if (!normalized.equals("auto")) return normalized;

        StringBuilder source = new StringBuilder();
        source.append(modelPath == null ? "" : modelPath).append('\n');
        source.append(model.getDescription() == null ? "" : model.getDescription()).append('\n');
        for (Map.Entry<String, String> entry : model.getMetadata().entrySet()) {
            source.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }

        String text = source.toString().toLowerCase();
        if (text.contains("qwen3.5") || text.contains("qwen3-5") || text.contains("qwen35")) return "qwen3.5";
        if (text.contains("qwen3") || (text.contains("qwen") && text.contains("/think"))) return "qwen3";
        if (text.contains("deepseek") && text.contains("r1")) return "deepseek-r1";
        return "generic";
    }

    public void submitTask(InferenceTask task) {
        if (!running) throw new IllegalStateException("Engine is shutting down");
        if (!taskQueue.offer(task)) {
            throw new RejectedExecutionException("Inference queue is full");
        }
    }

    LlamaCppContext createContext() {
        var ctxParams = LlamaCppContext.defaultContextParams()
                .with(ContextParam.n_ctx, contextSize)
                .with(ContextParam.n_threads, threadCount);
        return new LlamaCppContext(model, ctxParams);
    }

    LlamaCppModel getModel() { return model; }
    public String getEngineName() { return engineName; }
    public String getModelAlias() { return modelAlias; }
    public String getModelProfile() { return modelProfile; }
    public int getContextSize() { return contextSize; }
    public int getThreadCount() { return threadCount; }
    public int getGpuLayers() { return gpuLayers; }
    public boolean isRunning() { return running; }

    public void shutdown() {
        if (!running) return;
        running = false;
        System.out.println("[LlamaEngine:" + engineName + "] Shutting down...");
        inferenceExecutor.shutdown();
        try {
            if (!inferenceExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                inferenceExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            inferenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try { model.close(); } catch (Exception ignored) {}
        System.out.println("[LlamaEngine:" + engineName + "] Shutdown complete.");
    }

    public int getQueueSize() { return taskQueue.size(); }
    public int getMaxQueueSize() { return maxQueueSize; }
    public boolean hasQueueCapacity() { return taskQueue.remainingCapacity() > 0; }

    public boolean isModelLoaded() { return model != null; }

    public int getVocabularySize() { return model.getVocabularySize(); }
    public int getEmbeddingSize() { return model.getEmbeddingSize(); }
    public int getLayerCount() { return model.getLayerCount(); }
    public long getModelSize() { return model.getModelSize(); }
}
