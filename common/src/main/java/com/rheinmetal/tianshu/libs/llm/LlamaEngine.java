package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.LlamaCppContextPlan;
import org.argeo.jjml.llm.LlamaCppDevice;
import org.argeo.jjml.llm.LlamaCppModel;
import org.argeo.jjml.llm.LlamaCppModelInfo;
import org.argeo.jjml.llm.SpeculativeParams;
import org.argeo.jjml.llm.params.ContextParam;
import org.argeo.jjml.llm.params.ContextParams;
import org.argeo.jjml.llm.params.ModelParam;

import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
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
    private final LlamaCppModel mtpDraftModel;
    private final MtpDraftMaxCache.Key mtpDraftMaxCacheKey;
    private final LaneConfig chatLaneConfig;
    private final LaneConfig taskLaneConfig;
    private final int gpuLayers;
    private final String device;
    private final String modelAlias;
    private final String modelProfile;
    private final FlashAttentionMode flashAttentionMode;
    private final KvCacheType cacheTypeK;
    private final KvCacheType cacheTypeV;
    private final LlmContextBudgetPolicy contextBudgetPolicy;
    private final LlmContextBudgetPlan chatContextBudgetPlan;
    private final LlmContextBudgetPlan taskContextBudgetPlan;
    private final ContextParams chatContextParams;
    private final ContextParams taskContextParams;
    private final Consumer<InferenceEvent> inferenceEventListener;
    private final MtpAutoTuner mtpAutoTuner;

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
                        LlamaCppModel mtpDraftModel,
                        MtpDraftMaxCache.Key mtpDraftMaxCacheKey,
                        Integer cachedMtpDraftMax,
                        LaneConfig chatLaneConfig,
                        LaneConfig taskLaneConfig,
                        int gpuLayers,
                        String device,
                        String modelAlias,
                        String modelProfile,
                        FlashAttentionMode flashAttentionMode,
                        KvCacheType cacheTypeK,
                        KvCacheType cacheTypeV,
                        LlmContextBudgetPolicy contextBudgetPolicy,
                        LlmContextBudgetPlan chatContextBudgetPlan,
                        LlmContextBudgetPlan taskContextBudgetPlan,
                        ContextParams chatContextParams,
                        ContextParams taskContextParams,
                        Consumer<InferenceEvent> inferenceEventListener) {
        this.engineName = engineName;
        this.model = model;
        this.mtpDraftModel = mtpDraftModel;
        this.mtpDraftMaxCacheKey = mtpDraftMaxCacheKey;
        this.chatLaneConfig = chatLaneConfig;
        this.taskLaneConfig = taskLaneConfig;
        this.gpuLayers = gpuLayers;
        this.device = device;
        this.modelAlias = modelAlias;
        this.modelProfile = modelProfile;
        this.flashAttentionMode = flashAttentionMode != null ? flashAttentionMode : FlashAttentionMode.ENABLED;
        this.cacheTypeK = cacheTypeK;
        this.cacheTypeV = cacheTypeV;
        this.contextBudgetPolicy = contextBudgetPolicy != null ? contextBudgetPolicy : LlmContextBudgetPolicy.defaults();
        this.chatContextBudgetPlan = chatContextBudgetPlan != null ? chatContextBudgetPlan : LlmContextBudgetPlan.unavailable("Context preflight was not run");
        this.taskContextBudgetPlan = taskContextBudgetPlan != null ? taskContextBudgetPlan : LlmContextBudgetPlan.unavailable("Context preflight was not run");
        this.chatContextParams = chatContextParams;
        this.taskContextParams = taskContextParams;
        this.inferenceEventListener = inferenceEventListener;
        this.mtpAutoTuner = new MtpAutoTuner(
                safeSupportsMtp(model) || mtpDraftModel != null,
                safeMtpLayerCount(model, mtpDraftModel),
                cachedMtpDraftMax
        );
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
        return loadChatEngine(modelPath, chatLane, taskLane, gpuLayers, device, modelAlias, modelProfile, null, null, null);
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
                                             Consumer<InferenceEvent> inferenceEventListener) throws Exception {
        return loadChatEngine(
                modelPath,
                chatLaneConfig,
                taskLaneConfig,
                gpuLayers,
                device,
                modelAlias,
                modelProfile,
                FlashAttentionMode.ENABLED,
                cacheTypeK,
                cacheTypeV,
                LlmContextBudgetPolicy.defaults(),
                inferenceEventListener
        );
    }

    public static LlamaEngine loadChatEngine(String modelPath,
                                             LaneConfig chatLaneConfig,
                                             LaneConfig taskLaneConfig,
                                             int gpuLayers,
                                             String device,
                                             String modelAlias,
                                             String modelProfile,
                                             FlashAttentionMode flashAttentionMode,
                                             KvCacheType cacheTypeK,
                                             KvCacheType cacheTypeV,
                                             LlmContextBudgetPolicy contextBudgetPolicy,
                                             Consumer<InferenceEvent> inferenceEventListener) throws Exception {
        return loadChatEngine(
                modelPath,
                null,
                chatLaneConfig,
                taskLaneConfig,
                gpuLayers,
                device,
                modelAlias,
                modelProfile,
                flashAttentionMode,
                cacheTypeK,
                cacheTypeV,
                contextBudgetPolicy,
                inferenceEventListener
        );
    }

    public static LlamaEngine loadChatEngine(String modelPath,
                                             String mtpDraftModelPath,
                                             LaneConfig chatLaneConfig,
                                             LaneConfig taskLaneConfig,
                                             int gpuLayers,
                                             String device,
                                             String modelAlias,
                                             String modelProfile,
                                             FlashAttentionMode flashAttentionMode,
                                             KvCacheType cacheTypeK,
                                             KvCacheType cacheTypeV,
                                             LlmContextBudgetPolicy contextBudgetPolicy,
                                             Consumer<InferenceEvent> inferenceEventListener) throws Exception {
        device = DeviceSelector.normalize(device);
        FlashAttentionMode effectiveFlashAttentionMode = flashAttentionMode != null ? flashAttentionMode : FlashAttentionMode.ENABLED;
        LlmContextBudgetPolicy effectiveBudgetPolicy = contextBudgetPolicy != null ? contextBudgetPolicy : LlmContextBudgetPolicy.defaults();
        PreflightPlans preflight = preflightContextPlans(
                modelPath,
                chatLaneConfig,
                taskLaneConfig,
                gpuLayers,
                device,
                effectiveFlashAttentionMode,
                cacheTypeK,
                cacheTypeV,
                effectiveBudgetPolicy
        );
        requireRequestedContextAccepted(preflight.chatBudgetPlan(), InferenceLane.CHAT);
        requireRequestedContextAccepted(preflight.taskBudgetPlan(), InferenceLane.TASK);
        LlamaCppModel model = loadModel("chat", modelPath, gpuLayers, device);
        LlamaCppModel draftModel = null;
        String resolvedDraftPath = null;
        try {
            resolvedDraftPath = resolveMtpDraftModelPath(mtpDraftModelPath);
            if (resolvedDraftPath != null) {
                draftModel = loadModel("chat-mtp-draft", resolvedDraftPath, gpuLayers, device);
                System.out.println("[LlamaEngine:chat] External MTP draft loaded: " + resolvedDraftPath);
            }
            MtpDraftMaxCache.Key mtpDraftMaxCacheKey = MtpDraftMaxCache.key(modelPath, resolvedDraftPath, gpuLayers, device);
            Integer cachedMtpDraftMax = MtpDraftMaxCache.loadRecommendedDraftMax(mtpDraftMaxCacheKey);
            String resolvedModelProfile = resolveModelProfile(preflight.modelInfo(), modelPath, modelProfile);
            System.out.println("[LlamaEngine:chat] Model profile: " + resolvedModelProfile);
            LlamaEngine engine = new LlamaEngine(
                    "chat",
                    model,
                    draftModel,
                    mtpDraftMaxCacheKey,
                    cachedMtpDraftMax,
                    chatLaneConfig,
                    taskLaneConfig,
                    gpuLayers,
                    device,
                    modelAlias,
                    resolvedModelProfile,
                    effectiveFlashAttentionMode,
                    cacheTypeK,
                    cacheTypeV,
                    effectiveBudgetPolicy,
                    preflight.chatBudgetPlan(),
                    preflight.taskBudgetPlan(),
                    preflight.chatContextParams(),
                    preflight.taskContextParams(),
                    inferenceEventListener
            );
            engine.startWorker();
            return engine;
        } catch (Exception e) {
            if (draftModel != null) try { draftModel.close(); } catch (Exception ignored) {}
            try { model.close(); } catch (Exception ignored) {}
            throw e;
        }
    }

    private static LlamaCppModel loadModel(String engineName, String modelPath, int gpuLayers, String device) throws Exception {
        Path mp = Path.of(modelPath);
        if (!Files.exists(mp)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        System.out.println("[LlamaEngine:" + engineName + "] Loading model: " + modelPath);
        System.out.println("[LlamaEngine:" + engineName + "] GPU layers: " + gpuLayers);
        if (device != null) System.out.println("[LlamaEngine:" + engineName + "] Device: " + device);
        var modelParams = buildModelParams(gpuLayers, device);
        LlamaCppModel model = LlamaCppModel.loadAsync(mp, modelParams, progress -> {
            if (progress > 0) System.out.print("\r[LlamaEngine:" + engineName + "] Loading: " + (int) (progress * 100) + "%");
        }, null).get();
        System.out.println("\n[LlamaEngine:" + engineName + "] Model loaded: " + model.getDescription());
        return model;
    }

    private static boolean safeSupportsMtp(LlamaCppModel model) {
        try {
            return model.supportsMtp();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int safeMtpLayerCount(LlamaCppModel model) {
        try {
            return model.getMtpLayerCount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int safeMtpLayerCount(LlamaCppModel model, LlamaCppModel draftModel) {
        int targetLayers = safeMtpLayerCount(model);
        if (targetLayers > 0 || draftModel == null) return targetLayers;
        return safeMtpLayerCount(draftModel);
    }

    private static String resolveMtpDraftModelPath(String requestedDraftPath) throws Exception {
        if (requestedDraftPath == null || requestedDraftPath.isBlank()) {
            return null;
        }
        Path draftPath = Path.of(requestedDraftPath);
        if (!Files.exists(draftPath)) {
            throw new IllegalArgumentException("MTP draft model file not found: " + requestedDraftPath);
        }
        return draftPath.toString();
    }

    private void startWorker() {
        inferenceExecutor.submit(new TaskExecutor(this));
        System.out.println("[LlamaEngine:" + engineName + "] Inference worker started.");
    }

    private static String normalizeModelProfile(String modelProfile) {
        if (modelProfile == null || modelProfile.isBlank()) return "auto";
        return modelProfile.trim().toLowerCase();
    }

    private static String resolveModelProfile(LlamaCppModelInfo modelInfo, String modelPath, String requestedProfile) {
        String normalized = normalizeModelProfile(requestedProfile);
        if (!normalized.equals("auto")) return normalized;

        StringBuilder source = new StringBuilder();
        source.append(modelPath == null ? "" : modelPath).append('\n');
        source.append(modelInfo.description() == null ? "" : modelInfo.description()).append('\n');
        for (Map.Entry<String, String> entry : modelInfo.metadata().entrySet()) {
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
                RejectedExecutionException error = new RejectedExecutionException("chat inference queue is full");
                task.getSyncFuture().completeExceptionally(error);
                task.getGenerationFuture().completeExceptionally(error);
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
                task.getGenerationFuture().cancel(false);
                task.getMtpCalibrationFuture().cancel(false);
                publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
                publishInferenceEvent(task, InferenceEventType.CANCELLED, "Queued chat request was cancelled.");
                return;
            }
            notifyTaskArrival();
            return;
        }
        boolean removed = taskQueue.removeIf(queued -> queued.wraps(task));
        if (removed) {
            task.getSyncFuture().cancel(false);
            task.getGenerationFuture().cancel(false);
            task.getMtpCalibrationFuture().cancel(false);
            publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
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
        if (pendingChatTasks.get() > 0) return true;
        if (currentTask == null || !currentTask.isTaskPreemptible()) return false;
        return peekTaskPriority() > currentTask.getTaskPriority();
    }

    boolean shouldSuspendTaskLane() {
        return pendingChatTasks.get() > 0;
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
        return createContext(lane, InferenceOptions.defaults());
    }

    LlamaCppContext createContext(InferenceLane lane, InferenceOptions options) {
        ContextParams ctxParams = getPlannedContextParams(lane);
        int draftMax = resolveMtpDraftMax(options);
        if (draftMax > 0) {
            ctxParams = SpeculativeParams.adjustTargetContextParams(ctxParams, draftMax);
        }
        return new LlamaCppContext(model, ctxParams);
    }

    int resolveMtpDraftMax(InferenceOptions options) {
        if (options == null || !options.isMtpEnabled() || !mtpAutoTuner.isSupported()) return 0;
        Integer requested = options.getMtpDraftMax();
        return requested != null ? requested : mtpAutoTuner.recommendedDraftMax();
    }

    boolean supportsMtpInternal() {
        return mtpAutoTuner.isSupported();
    }

    void recordMtpTrial(MtpTrialResult trial) {
        if (mtpAutoTuner.record(trial)) {
            MtpDraftMaxCache.saveRecommendedDraftMax(mtpDraftMaxCacheKey, trial);
        }
    }

    PromptSnapshot promptSnapshot(List<LlamaCppChatMessage> messages, SamplerConfig config) {
        return promptSnapshot(messages, config, null);
    }

    PromptSnapshot promptSnapshot(List<LlamaCppChatMessage> messages, SamplerConfig config, InferenceOptions options) {
        synchronized (model) {
            return ChatPromptTemplate.snapshot(model, messages, config, options);
        }
    }

    public int countChatPromptTokens(List<LlamaCppChatMessage> messages, SamplerConfig config) {
        return countChatPromptTokens(messages, config, null);
    }

    public int countChatPromptTokens(List<LlamaCppChatMessage> messages, SamplerConfig config, InferenceOptions options) {
        if (!running) throw new IllegalStateException("Engine is shutting down");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages cannot be null or empty");
        }
        SamplerConfig effectiveConfig = config != null ? config.copy() : new SamplerConfig();
        effectiveConfig.validate();
        synchronized (model) {
            return ChatPromptTemplate.countTokens(model, messages, effectiveConfig, options);
        }
    }

    LlamaCppModel getModel() { return model; }
    public String getEngineName() { return engineName; }
    public String getModelAlias() { return modelAlias; }
    public String getModelProfile() { return modelProfile; }
    public int getContextSize() { return chatLaneConfig.getContextSize(); }
    public int getThreadCount() { return chatLaneConfig.getThreadCount(); }
    public int getGpuLayers() { return gpuLayers; }
    public String getDevice() { return device; }
    public FlashAttentionMode getFlashAttentionMode() { return flashAttentionMode; }
    public LaneConfig getChatLaneConfig() { return chatLaneConfig; }
    public LaneConfig getTaskLaneConfig() { return taskLaneConfig; }
    public KvCacheType getCacheTypeK() { return cacheTypeK; }
    public KvCacheType getCacheTypeV() { return cacheTypeV; }
    LlmContextBudgetPolicy getContextBudgetPolicy() { return contextBudgetPolicy; }
    public boolean isRunning() { return running; }

    public boolean supportsEnableThinking() {
        return model.supportsEnableThinking();
    }

    public LlmRuntimeCapabilities getRuntimeCapabilities() {
        try {
            return new LlmRuntimeCapabilities(
                    running && isModelLoaded(),
                    safeSupportsEnableThinking(),
                    supportsMtpInternal(),
                    safeSupportsMtp(model),
                    hasExternalMtpDraftModel(),
                    getMtpLayerCount()
            );
        } catch (RuntimeException e) {
            return LlmRuntimeCapabilities.unavailable();
        }
    }

    public LlmContextBudgetPlan getContextBudgetPlan() {
        return getContextBudgetPlan(InferenceLane.CHAT);
    }

    public LlmContextBudgetPlan getContextBudgetPlan(InferenceLane lane) {
        return lane == InferenceLane.TASK ? taskContextBudgetPlan : chatContextBudgetPlan;
    }

    int getPlannedContextSize(InferenceLane lane) {
        LlmContextBudgetPlan plan = getContextBudgetPlan(lane);
        return plan.plannedContextSize() > 0 ? plan.plannedContextSize()
                : (lane == InferenceLane.TASK ? taskLaneConfig : chatLaneConfig).getContextSize();
    }

    private ContextParams getPlannedContextParams(InferenceLane lane) {
        ContextParams params = lane == InferenceLane.TASK ? taskContextParams : chatContextParams;
        if (params != null) return params;
        LaneConfig config = lane == InferenceLane.TASK ? taskLaneConfig : chatLaneConfig;
        return buildContextParams(
                config,
                getPlannedContextSize(lane),
                flashAttentionMode,
                cacheTypeK,
                cacheTypeV
        );
    }

    private boolean safeSupportsEnableThinking() {
        try {
            return model.supportsEnableThinking();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static org.argeo.jjml.llm.params.ModelParams buildModelParams(int gpuLayers, String device) {
        var modelParams = LlamaCppModel.defaultModelParams()
                .with(ModelParam.n_gpu_layers, gpuLayers);
        if (device != null) modelParams = modelParams.with(ModelParam.device, device);
        return modelParams;
    }

    private static ContextParams buildContextParams(LaneConfig config,
                                                    int contextSize,
                                                    FlashAttentionMode flashAttentionMode,
                                                    KvCacheType cacheTypeK,
                                                    KvCacheType cacheTypeV) {
        ContextParams params = LlamaCppContext.defaultContextParams()
                .with(ContextParam.n_ctx, contextSize)
                .with(ContextParam.n_threads, config.getThreadCount())
                .with(ContextParam.flash_attn_type, flashAttentionMode.getJjmlType());
        if (cacheTypeK != null) params = params.with(ContextParam.type_k, cacheTypeK.getGgmlType());
        if (cacheTypeV != null) params = params.with(ContextParam.type_v, cacheTypeV.getGgmlType());
        return params;
    }

    public static LlmContextBudgetPlan dryRunContextBudget(String modelPath,
                                                           int contextSize,
                                                           int threadCount,
                                                           int gpuLayers,
                                                           String device,
                                                           FlashAttentionMode flashAttentionMode,
                                                           KvCacheType cacheTypeK,
                                                           KvCacheType cacheTypeV,
                                                           LlmContextBudgetPolicy policy) throws Exception {
        device = DeviceSelector.normalize(device);
        FlashAttentionMode effectiveFlashAttentionMode = flashAttentionMode != null ? flashAttentionMode : FlashAttentionMode.ENABLED;
        LlmContextBudgetPolicy effectivePolicy = policy != null ? policy : LlmContextBudgetPolicy.defaults();
        LaneConfig laneConfig = new LaneConfig(InferenceLane.CHAT, contextSize, threadCount, 1);
        Path path = Path.of(modelPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        LlamaCppContextPlan plan = LlamaCppModel.planContext(
                path,
                buildModelParams(gpuLayers, device),
                buildContextParams(laneConfig, contextSize, effectiveFlashAttentionMode, cacheTypeK, cacheTypeV)
        );
        return toBudgetPlan(plan, laneConfig, effectivePolicy);
    }

    private static PreflightPlans preflightContextPlans(String modelPath,
                                                        LaneConfig chatLaneConfig,
                                                        LaneConfig taskLaneConfig,
                                                        int gpuLayers,
                                                        String device,
                                                        FlashAttentionMode flashAttentionMode,
                                                        KvCacheType cacheTypeK,
                                                        KvCacheType cacheTypeV,
                                                        LlmContextBudgetPolicy policy) throws Exception {
        Path path = Path.of(modelPath);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Model file not found: " + modelPath);
        }
        var modelParams = buildModelParams(gpuLayers, device);
        LlamaCppContextPlan chatPlan = LlamaCppModel.planContext(
                path,
                modelParams,
                buildContextParams(chatLaneConfig, chatLaneConfig.getContextSize(), flashAttentionMode, cacheTypeK, cacheTypeV)
        );
        LlamaCppContextPlan taskPlan = LlamaCppModel.planContext(
                path,
                modelParams,
                buildContextParams(taskLaneConfig, taskLaneConfig.getContextSize(), flashAttentionMode, cacheTypeK, cacheTypeV)
        );
        return new PreflightPlans(
                chatPlan.modelInfo(),
                toBudgetPlan(chatPlan, chatLaneConfig, policy),
                toBudgetPlan(taskPlan, taskLaneConfig, policy),
                chatPlan.contextParams(),
                taskPlan.contextParams()
        );
    }

    private static void requireRequestedContextAccepted(LlmContextBudgetPlan plan, InferenceLane lane) {
        if (plan == null || !plan.reliable()) return;
        if (plan.plannedContextSize() == plan.requestedContextSize()) return;
        throw new IllegalArgumentException(lane.wireName() + " requested context size "
                + plan.requestedContextSize()
                + " exceeds dryrun planned context size "
                + plan.plannedContextSize()
                + "; run dryRunContextBudget first and pass the returned plannedContextSize as contextSize");
    }

    private static LlmContextBudgetPlan toBudgetPlan(LlamaCppContextPlan plan,
                                                     LaneConfig config,
                                                     LlmContextBudgetPolicy policy) {
        LlamaCppDevice device = primaryDevice(plan.devices());
        long freeBytes = device == null ? -1L : device.memoryFree();
        long kvBytesPerToken = plan.kvCacheBytesPerToken();
        long fixedDeviceBytes = fixedDeviceBytes(plan.deviceBytes(), plan.kvCacheBytes());
        int memoryContextSize = estimateMemoryContextSize(freeBytes, policy.safetyMarginBytes(), fixedDeviceBytes, kvBytesPerToken);
        boolean planHasContext = plan.contextSize() > 0;
        boolean reliable = planHasContext || (kvBytesPerToken > 0L && memoryContextSize > 0);
        int requestedContextSize = config.getContextSize();
        int plannedContextSize = planHasContext
                ? Math.min(requestedContextSize, plan.contextSize())
                : reliable
                ? Math.min(requestedContextSize, memoryContextSize)
                : requestedContextSize;
        int promptTokenBudget = Math.max(0, plannedContextSize - policy.promptMarginTokens());
        String limitation = reliable ? "" : "device memory or context preflight estimate is unavailable";
        return new LlmContextBudgetPlan(
                requestedContextSize,
                plan.contextTrainingSize(),
                memoryContextSize,
                plannedContextSize,
                promptTokenBudget,
                policy.promptMarginTokens(),
                policy.safetyMarginBytes(),
                reliable,
                limitation
        );
    }

    private static long fixedDeviceBytes(long deviceBytes, long kvCacheBytes) {
        if (deviceBytes < 0L || kvCacheBytes < 0L) return -1L;
        return Math.max(0L, deviceBytes - kvCacheBytes);
    }

    private static int estimateMemoryContextSize(long freeBytes, long safetyMarginBytes, long fixedDeviceBytes, long kvBytesPerToken) {
        if (freeBytes < 0L || kvBytesPerToken <= 0L) return -1;
        long available = freeBytes - Math.max(0L, safetyMarginBytes);
        if (fixedDeviceBytes > 0L) available -= fixedDeviceBytes;
        if (available <= 0L) return 0;
        return available > Integer.MAX_VALUE * kvBytesPerToken
                ? Integer.MAX_VALUE
                : (int) (available / kvBytesPerToken);
    }

    private static LlamaCppDevice primaryDevice(LlamaCppDevice[] devices) {
        if (devices == null || devices.length == 0) return null;
        for (LlamaCppDevice candidate : devices) {
            if (candidate != null && candidate.memoryTotal() > 0L) {
                return candidate;
            }
        }
        return devices[0];
    }

    private record PreflightPlans(
            LlamaCppModelInfo modelInfo,
            LlmContextBudgetPlan chatBudgetPlan,
            LlmContextBudgetPlan taskBudgetPlan,
            ContextParams chatContextParams,
            ContextParams taskContextParams
    ) {
    }

    public boolean supportsMtp() {
        return mtpAutoTuner.isSupported();
    }

    boolean supportsEmbeddedMtp() {
        return safeSupportsMtp(model);
    }

    boolean hasExternalMtpDraftModel() {
        return mtpDraftModel != null;
    }

    LlamaCppModel getMtpDraftModel() {
        return mtpDraftModel;
    }

    public int getMtpLayerCount() {
        return mtpAutoTuner.getMtpLayerCount();
    }

    public MtpCapability getMtpCapability() {
        return mtpAutoTuner.capability();
    }

    public void resetMtpCalibration() {
        mtpAutoTuner.reset();
    }

    public java.util.concurrent.CompletableFuture<MtpCalibrationResult> calibrateMtpAsync(MtpCalibrationRequest request) {
        InferenceTask task = InferenceTask.mtpCalibration(request);
        submitTask(task);
        java.util.concurrent.CompletableFuture<MtpCalibrationResult> exposed = new java.util.concurrent.CompletableFuture<>();
        task.getMtpCalibrationFuture().whenComplete((result, error) -> {
            if (error != null) {
                exposed.completeExceptionally(error);
            } else {
                exposed.complete(result);
            }
        });
        exposed.whenComplete((result, error) -> {
            if (exposed.isCancelled() && !task.getMtpCalibrationFuture().isDone()) {
                cancelTask(task);
            }
        });
        return exposed;
    }

    public void shutdown() {
        if (!running) return;
        running = false;
        InferenceTask active = currentTask;
        if (active != null) {
            active.cancel();
            active.getSyncFuture().cancel(false);
            active.getGenerationFuture().cancel(false);
            active.getMtpCalibrationFuture().cancel(false);
            publishStreamFinish(active, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
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
            if (mtpDraftModel != null) {
                try { mtpDraftModel.close(); } catch (Exception ignored) {}
            }
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
            chatTask.getGenerationFuture().cancel(false);
            chatTask.getMtpCalibrationFuture().cancel(false);
            publishStreamFinish(chatTask, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
            publishInferenceEvent(chatTask, InferenceEventType.CANCELLED, "Queued chat request was cancelled during shutdown.");
        }

        PrioritizedInferenceTask taskTask;
        while ((taskTask = taskQueue.poll()) != null) {
            InferenceTask task = taskTask.getTask();
            task.cancel();
            task.getSyncFuture().cancel(false);
            task.getGenerationFuture().cancel(false);
            task.getMtpCalibrationFuture().cancel(false);
            publishStreamFinish(task, StreamFinishType.CANCELLED, new LlmTokenUsage(0, 0), null);
            publishInferenceEvent(task, InferenceEventType.CANCELLED, "Queued task request was cancelled during shutdown.");
            finishTask(task);
        }
    }

    private void publishStreamFinish(InferenceTask task, StreamFinishType type, LlmTokenUsage usage, Throwable error) {
        if (task == null || task.getFinishCallback() == null) return;
        try {
            task.getFinishCallback().accept(new LlmStreamFinish(type, usage, error));
        } catch (Exception ignored) {
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
