package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ContextParams;
import org.argeo.jjml.llm.params.ModelParam;

import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class LlamaEngine {

    private final String engineName;
    private final LlamaCppModel model;
    private final LaneConfig chatLaneConfig;
    private final LaneConfig taskLaneConfig;
    private final int gpuLayers;
    private final String device;
    private final String modelAlias;
    private final String modelProfile;
    private final KvCacheType cacheTypeK;
    private final KvCacheType cacheTypeV;
    private final boolean taskSuspendOnChat;
    private final Consumer<InferenceEvent> inferenceEventListener;

    private final LinkedBlockingQueue<InferenceTask> chatQueue;
    private final PriorityBlockingQueue<PrioritizedInferenceTask> taskQueue;
    private final AtomicInteger pendingChatTasks = new AtomicInteger(0);
    private final ExecutorService inferenceExecutor;
    private final Object taskArrivalLock = new Object();
    private volatile boolean hasPendingTasks = false;
    private volatile boolean running = true;
    private volatile InferenceLane currentLane;
    private volatile InferenceTask currentTask;
    private volatile boolean taskSuspended;

    static {
        NativeLibraryLoader.ensureLoaded();
    }

    private LlamaEngine(String engineName,
                        LlamaCppModel model,
                        LaneConfig chatLaneConfig,
                        LaneConfig taskLaneConfig,
                        int gpuLayers,
                        String device,
                        String modelAlias,
                        String modelProfile,
                        KvCacheType cacheTypeK,
                        KvCacheType cacheTypeV,
                        boolean taskSuspendOnChat,
                        Consumer<InferenceEvent> inferenceEventListener) {
        this.engineName = engineName;
        this.model = model;
        this.chatLaneConfig = chatLaneConfig;
        this.taskLaneConfig = taskLaneConfig;
        this.gpuLayers = gpuLayers;
        this.device = device;
        this.modelAlias = modelAlias;
        this.modelProfile = modelProfile;
        this.cacheTypeK = cacheTypeK;
        this.cacheTypeV = cacheTypeV;
        this.taskSuspendOnChat = taskSuspendOnChat;
        this.inferenceEventListener = inferenceEventListener;
        this.chatQueue = new LinkedBlockingQueue<>(chatLaneConfig.getMaxQueueSize());
        this.taskQueue = new PriorityBlockingQueue<>();
        this.inferenceExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, engineName + "-inference-worker");
            t.setDaemon(false);
            return t;
        });
    }

    public static LlamaEngine loadChatEngine(String modelPath, int contextSize, int threadCount,
                                              int gpuLayers, String modelAlias, String modelProfile) throws Exception {
        return loadChatEngine(modelPath, contextSize, threadCount, gpuLayers, null, modelAlias, modelProfile, 4);
    }

    public static LlamaEngine loadChatEngine(String modelPath, int contextSize, int threadCount,
                                              int gpuLayers, String modelAlias, String modelProfile, int maxQueueSize) throws Exception {
        return loadChatEngine(modelPath, contextSize, threadCount, gpuLayers, null, modelAlias, modelProfile, maxQueueSize);
    }

    public static LlamaEngine loadChatEngine(String modelPath, int contextSize, int threadCount,
                                             int gpuLayers, String device, String modelAlias, String modelProfile, int maxQueueSize) throws Exception {
        LaneConfig chatLane = new LaneConfig(InferenceLane.CHAT, contextSize, threadCount, maxQueueSize);
        LaneConfig taskLane = new LaneConfig(InferenceLane.TASK, contextSize, Math.max(1, threadCount), 0);
        return loadChatEngine(modelPath, chatLane, taskLane, gpuLayers, device, modelAlias, modelProfile, null, null, true, null);
    }

    public static LlamaEngine loadChatEngine(String modelPath,
                                             LaneConfig chatLaneConfig,
                                             LaneConfig taskLaneConfig,
                                             int gpuLayers,
                                             String device,
                                             String modelAlias,
                                             String modelProfile,
                                             KvCacheType cacheTypeK,
                                             KvCacheType cacheTypeV,
                                             boolean taskSuspendOnChat,
                                             Consumer<InferenceEvent> inferenceEventListener) throws Exception {
        LlamaCppModel model = loadModel("chat", modelPath, gpuLayers, device);
        String resolvedModelProfile = resolveModelProfile(model, modelPath, modelProfile);
        System.out.println("[LlamaEngine:chat] Model profile: " + resolvedModelProfile);
        LlamaEngine engine = new LlamaEngine("chat", model, chatLaneConfig, taskLaneConfig, gpuLayers, device, modelAlias, resolvedModelProfile, cacheTypeK, cacheTypeV, taskSuspendOnChat, inferenceEventListener);
        engine.startWorker();
        return engine;
    }

    private static LlamaCppModel loadModel(String engineName, String modelPath, int gpuLayers, String device) throws Exception {
        Path mp = Path.of(modelPath);
        if (!Files.exists(mp)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        System.out.println("[LlamaEngine:" + engineName + "] Loading model: " + modelPath);
        System.out.println("[LlamaEngine:" + engineName + "] GPU layers: " + gpuLayers);
        if (device != null) System.out.println("[LlamaEngine:" + engineName + "] Device: " + device);
        var modelParams = LlamaCppModel.defaultModelParams()
                .with(ModelParam.n_gpu_layers, gpuLayers);
        if (device != null) modelParams = modelParams.with(ModelParam.device, device);
        LlamaCppModel model = LlamaCppModel.loadAsync(mp, modelParams, progress -> {
            if (progress > 0) System.out.print("\r[LlamaEngine:" + engineName + "] Loading: " + (int) (progress * 100) + "%");
        }, null).get();
        System.out.println("\n[LlamaEngine:" + engineName + "] Model loaded: " + model.getDescription());
        return model;
    }

    private void startWorker() {
        inferenceExecutor.submit(new TaskExecutor(this));
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
        if (task == null) throw new IllegalArgumentException("task is required");
        if (task.getLane() == InferenceLane.CHAT) {
            pendingChatTasks.incrementAndGet();
            boolean accepted = chatQueue.offer(task);
            if (!accepted) {
                pendingChatTasks.decrementAndGet();
                throw new RejectedExecutionException("chat inference queue is full");
            }
            publishInferenceEvent(task, InferenceEventType.QUEUED, "Chat request queued.");
            notifyTaskArrival();
            return;
        }
        
        boolean accepted = taskQueue.offer(new PrioritizedInferenceTask(task));
        if (!accepted) {
            throw new RejectedExecutionException("task inference queue is full");
        }
        publishInferenceEvent(task, InferenceEventType.QUEUED, "Task request queued.");
        notifyTaskArrival();
    }

    InferenceTask pollChatTaskNonBlocking() {
        InferenceTask task = chatQueue.poll();
        if (task != null) pendingChatTasks.decrementAndGet();
        return task;
    }

    InferenceTask pollTaskTask() {
        PrioritizedInferenceTask prioritizedTask = taskQueue.poll();
        if (prioritizedTask == null) return null;
        return prioritizedTask.getTask();
    }

    void requeueTask(InferenceTask task) {
        if (task == null) return;
        if (task.getLane() == InferenceLane.CHAT) {
            pendingChatTasks.incrementAndGet();
            boolean accepted = chatQueue.offer(task);
            if (!accepted) {
                pendingChatTasks.decrementAndGet();
                task.cancel();
                task.getSyncFuture().completeExceptionally(new RejectedExecutionException("chat inference queue is full"));
                return;
            }
            publishInferenceEvent(task, InferenceEventType.QUEUED, "Chat request requeued.");
        } else {
            taskQueue.offer(new PrioritizedInferenceTask(task));
            publishInferenceEvent(task, InferenceEventType.QUEUED, "Task request requeued.");
        }
        notifyTaskArrival();
    }

    public void cancelTask(InferenceTask task) {
        if (task == null) return;
        task.cancel();
        if (task.getLane() == InferenceLane.CHAT) {
            if (chatQueue.remove(task)) {
                pendingChatTasks.decrementAndGet();
                task.getSyncFuture().cancel(false);
                publishInferenceEvent(task, InferenceEventType.CANCELLED, "Queued chat request was cancelled.");
                return;
            }
            notifyTaskArrival();
            return;
        }
        boolean removed = taskQueue.removeIf(queued -> queued.wraps(task));
        if (removed) {
            task.getSyncFuture().cancel(false);
            publishInferenceEvent(task, InferenceEventType.CANCELLED, "Queued task request was cancelled.");
            finishTask(task);
            notifyTaskArrival();
            return;
        }
        notifyTaskArrival();
    }

    void finishTask(InferenceTask task) {
    }

    int getTaskQueueSizeInternal() {
        return taskQueue.size();
    }

    int getHotSuspendLimit() {
        return taskLaneConfig.getMaxQueueSize();
    }

    void publishInferenceEvent(InferenceTask task, InferenceEventType type, String message) {
        publishInferenceEvent(task, type, message, 0, 0, null);
    }

    void publishInferenceEvent(InferenceTask task, InferenceEventType type, String message, Throwable error) {
        publishInferenceEvent(task, type, message, 0, 0, error);
    }

    void publishInferenceEvent(InferenceTask task,
                               InferenceEventType type,
                               String message,
                               int replayCharacters,
                               int generatedTokens,
                               Throwable error) {
        Consumer<InferenceEvent> listener = inferenceEventListener;
        if (listener == null || task == null) return;
        try {
            listener.accept(new InferenceEvent(
                    task.getTaskId(),
                    task.getTaskType(),
                    task.getLane(),
                    task.getTaskPriority(),
                    type,
                    message,
                    replayCharacters,
                    generatedTokens,
                    error
            ));
        } catch (Exception ignored) {
        }
    }

    void notifyTaskArrival() {
        hasPendingTasks = true;
        synchronized (taskArrivalLock) {
            taskArrivalLock.notifyAll();
        }
    }

    void awaitTaskArrival() throws InterruptedException {
        if (hasPendingTasks) {
            hasPendingTasks = false;
            return;
        }
        synchronized (taskArrivalLock) {
            while (!hasPendingTasks && running) {
                taskArrivalLock.wait();
            }
            hasPendingTasks = false;
        }
    }

    public int peekTaskPriority() {
        PrioritizedInferenceTask task = taskQueue.peek();
        return task == null ? Integer.MIN_VALUE : task.getTask().getTaskPriority();
    }

    boolean shouldSuspendTaskLane(InferenceTask currentTask) {
        if (taskSuspendOnChat && pendingChatTasks.get() > 0) return true;
        if (currentTask == null || !currentTask.isTaskPreemptible()) return false;
        return peekTaskPriority() > currentTask.getTaskPriority();
    }

    boolean shouldSuspendTaskLane() {
        return taskSuspendOnChat && pendingChatTasks.get() > 0;
    }

    void setCurrentLane(InferenceLane lane) {
        currentLane = lane;
    }

    void setCurrentTask(InferenceTask task) {
        currentTask = task;
    }

    void setTaskSuspended(boolean taskSuspended) {
        this.taskSuspended = taskSuspended;
    }

    LlamaCppContext createContext(InferenceLane lane) {
        LaneConfig config = lane == InferenceLane.TASK ? taskLaneConfig : chatLaneConfig;
        ContextParams ctxParams = LlamaCppContext.defaultContextParams()
                .with(ContextParam.n_ctx, config.getContextSize())
                .with(ContextParam.n_threads, config.getThreadCount());
        if (cacheTypeK != null) ctxParams = ctxParams.with(ContextParam.type_k, cacheTypeK.getGgmlType());
        if (cacheTypeV != null) ctxParams = ctxParams.with(ContextParam.type_v, cacheTypeV.getGgmlType());
        return new LlamaCppContext(model, ctxParams);
    }

    LlamaCppModel getModel() { return model; }
    public String getEngineName() { return engineName; }
    public String getModelAlias() { return modelAlias; }
    public String getModelProfile() { return modelProfile; }
    public int getContextSize() { return chatLaneConfig.getContextSize(); }
    public int getThreadCount() { return chatLaneConfig.getThreadCount(); }
    public int getGpuLayers() { return gpuLayers; }
    public String getDevice() { return device; }
    public LaneConfig getChatLaneConfig() { return chatLaneConfig; }
    public LaneConfig getTaskLaneConfig() { return taskLaneConfig; }
    public KvCacheType getCacheTypeK() { return cacheTypeK; }
    public KvCacheType getCacheTypeV() { return cacheTypeV; }
    public boolean isTaskSuspendOnChat() { return taskSuspendOnChat; }
    public boolean isRunning() { return running; }

    public boolean supportsEnableThinking() {
        return model.supportsEnableThinking();
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        InferenceTask active = currentTask;
        if (active != null) {
            active.cancel();
            active.getSyncFuture().cancel(false);
            publishInferenceEvent(active, InferenceEventType.CANCELLED, "Active inference was cancelled during shutdown.");
            finishTask(active);
        }
        cancelQueuedTasks();
        notifyTaskArrival();
        System.out.println("[LlamaEngine:" + engineName + "] Shutting down...");
        inferenceExecutor.shutdown();
        boolean terminated = false;
        try {
            terminated = inferenceExecutor.awaitTermination(30, TimeUnit.SECONDS);
            if (!terminated) {
                inferenceExecutor.shutdownNow();
                terminated = inferenceExecutor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            inferenceExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (terminated) {
            try { model.close(); } catch (Exception ignored) {}
            System.out.println("[LlamaEngine:" + engineName + "] Shutdown complete.");
        } else {
            System.err.println("[LlamaEngine:" + engineName + "] Worker did not stop; model close skipped to avoid native use-after-close.");
        }
    }

    private void cancelQueuedTasks() {
        InferenceTask chatTask;
        while ((chatTask = chatQueue.poll()) != null) {
            pendingChatTasks.decrementAndGet();
            chatTask.cancel();
            chatTask.getSyncFuture().cancel(false);
            publishInferenceEvent(chatTask, InferenceEventType.CANCELLED, "Queued chat request was cancelled during shutdown.");
        }

        PrioritizedInferenceTask taskTask;
        while ((taskTask = taskQueue.poll()) != null) {
            InferenceTask task = taskTask.getTask();
            task.cancel();
            task.getSyncFuture().cancel(false);
            publishInferenceEvent(task, InferenceEventType.CANCELLED, "Queued task request was cancelled during shutdown.");
            finishTask(task);
        }
    }

    public int getQueueSize() { return getChatQueueSize(); }
    public int getMaxQueueSize() { return chatLaneConfig.getMaxQueueSize(); }
    public boolean hasQueueCapacity() { return hasQueueCapacity(InferenceLane.CHAT); }
    public int getChatQueueSize() { return chatQueue.size(); }
    public int getTaskLoad() { return taskQueue.size(); }
    public int getTaskQueueSize() { return getTaskLoad(); }
    public boolean hasQueueCapacity(InferenceLane lane) {
        return lane == InferenceLane.TASK 
            ? true
            : chatQueue.remainingCapacity() > 0;
    }

    public LaneMetrics getLaneMetrics() {
        return new LaneMetrics(chatQueue.size(), chatLaneConfig.getMaxQueueSize(), taskQueue.size(), taskLaneConfig.getMaxQueueSize(), currentLane, taskSuspended);
    }

    public boolean isModelLoaded() { return model != null; }

    public int getVocabularySize() { return model.getVocabularySize(); }
    public int getEmbeddingSize() { return model.getEmbeddingSize(); }
    public int getLayerCount() { return model.getLayerCount(); }
    public long getModelSize() { return model.getModelSize(); }
}
