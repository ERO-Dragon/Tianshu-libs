package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.DeviceSelector;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.FlashAttentionMode;
import com.rheinmetal.tianshu.libs.llm.InferenceEvent;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.InferenceOptions;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.libs.llm.LaneConfig;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.LlmGenerationResult;
import com.rheinmetal.tianshu.libs.llm.LlmStreamFinish;
import com.rheinmetal.tianshu.libs.llm.LlmContextBudgetPlan;
import com.rheinmetal.tianshu.libs.llm.LlmContextBudgetPolicy;
import com.rheinmetal.tianshu.libs.llm.LlmRuntimeCapabilities;
import com.rheinmetal.tianshu.libs.llm.ModelRegistry;
import com.rheinmetal.tianshu.libs.llm.MtpCalibrationRequest;
import com.rheinmetal.tianshu.libs.llm.MtpCalibrationResult;
import com.rheinmetal.tianshu.libs.llm.MtpCapability;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Facade over the local llama.cpp engines: at most one chat model and at most one embedding model.
 *
 * <p>Two shapes are supported. Configuring both models behaves as it always has. Configuring only an
 * embedding model keeps local vectors for RAG while chat is served elsewhere (for example a cloud API):
 * {@link #start()} succeeds, {@link #isStarted()} is true, {@link #hasEmbeddingEngine()} is true,
 * {@link #hasChatEngine()} is false, {@link #isReady()} is false, and every chat, task, streaming,
 * token-counting, and MTP-calibration call fails with a readable
 * {@link ModelRegistry#CHAT_MODEL_NOT_CONFIGURED} error instead of a NullPointerException.
 */
public class JavaLlamaServer {
    static {
        NativeLibraryLoader.ensureLoaded();
    }

    private final ServerConfig config;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ModelRegistry models;
    private volatile LibsApi libsApi;

    private JavaLlamaServer(ServerConfig config) {
        this.config = copyConfig(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Plans the chat context budget without loading the chat model.
     *
     * @throws IllegalStateException when the configuration has no chat model to plan for
     */
    public static LlmContextBudgetPlan dryRunContextBudget(ServerConfig config) throws Exception {
        ServerConfig.validateOrThrow(config);
        return dryRunContextBudgetInternal(copyConfig(config));
    }

    private static LlmContextBudgetPlan dryRunContextBudgetInternal(ServerConfig config) throws Exception {
        if (config.modelPath == null || config.modelPath.isBlank()) {
            throw new IllegalStateException(ModelRegistry.CHAT_MODEL_NOT_CONFIGURED);
        }
        return LlamaEngine.dryRunContextBudget(
                config.modelPath,
                config.contextSize,
                config.chatThreads,
                config.gpuLayers,
                config.device,
                config.flashAttentionMode,
                config.cacheTypeK,
                config.cacheTypeV,
                config.contextBudgetPolicy
        );
    }

    /**
     * Loads the configured models and makes this instance serve them.
     *
     * <p>A chat model is optional: with only an embedding model configured, {@code start()} succeeds and
     * the instance serves {@link #embed(String)} and {@link #search} while every chat entry point fails
     * with a readable "chat model is not configured" error. See {@link #isStarted()},
     * {@link #hasChatEngine()}, and {@link #hasEmbeddingEngine()} to tell the modes apart.
     */
    public synchronized void start() throws Exception {
        if (started.get()) return;
        ServerConfig.validateOrThrow(config);
        if (!started.compareAndSet(false, true)) return;

        LlamaEngine engine = null;
        EmbeddingEngine embeddingEngine = null;
        ModelRegistry localModels = null;
        LibsApi localLibsApi = null;
        try {
            if (config.modelPath != null && !config.modelPath.isBlank()) {
                String modelAlias = config.modelAlias;
                if (modelAlias == null || modelAlias.isBlank() || modelAlias.equals("unknown")) {
                    modelAlias = extractFileName(config.modelPath);
                }

                LaneConfig chatLaneConfig = new LaneConfig(InferenceLane.CHAT, config.contextSize, config.chatThreads, config.chatMaxQueueSize);
                LaneConfig taskLaneConfig = new LaneConfig(InferenceLane.TASK, config.contextSize, config.taskThreads, ServerConfig.TASK_HOT_SUSPEND_SLOTS);
                engine = LlamaEngine.loadChatEngine(
                        config.modelPath,
                        config.mtpDraftModelPath,
                        chatLaneConfig,
                        taskLaneConfig,
                        config.gpuLayers,
                        config.device,
                        modelAlias,
                        config.modelProfile,
                        config.flashAttentionMode,
                        config.cacheTypeK,
                        config.cacheTypeV,
                        config.contextBudgetPolicy,
                        config.inferenceEventListener
                );
            }

            if (config.embeddingModelPath != null && !config.embeddingModelPath.isBlank()) {
                String embeddingAlias = config.embeddingAlias;
                if (embeddingAlias == null || embeddingAlias.isBlank() || embeddingAlias.equals("embedding")) {
                    embeddingAlias = extractFileName(config.embeddingModelPath);
                }
                embeddingEngine = EmbeddingEngine.load(
                        config.embeddingModelPath,
                        config.embeddingContextSize,
                        config.embeddingThreads,
                        config.embeddingGpuLayers,
                        config.embeddingDevice,
                        embeddingAlias
                );
            }

            localModels = new ModelRegistry(engine, embeddingEngine);
            localLibsApi = new LibsApi(engine, embeddingEngine, config.requestTimeoutSeconds);

            this.models = localModels;
            this.libsApi = localLibsApi;
        } catch (Exception e) {
            try {
                if (localModels != null) {
                    localModels.shutdown();
                } else {
                    if (embeddingEngine != null) embeddingEngine.shutdown();
                    if (engine != null) engine.shutdown();
                }
            } catch (Exception ignored) {
            }
            started.set(false);
            throw e;
        }
    }

    public synchronized void shutdown() {
        ModelRegistry currentModels = this.models;
        this.models = null;
        this.libsApi = null;
        if (currentModels != null) {
            try {
                currentModels.shutdown();
            } catch (Exception ignored) {
            }
        }
        started.set(false);
    }

    /**
     * Runs one chat completion.
     *
     * @throws IllegalStateException when the server is not started; the returned future completes
     *         exceptionally with {@link ModelRegistry#CHAT_MODEL_NOT_CONFIGURED} when the server was
     *         started without a chat model
     */
    public CompletableFuture<String> chat(String message, String systemPrompt) {
        return chat(toMessages(message, systemPrompt));
    }

    public CompletableFuture<String> chat(String message, String systemPrompt, ThinkingMode thinkingMode) {
        SamplerConfig sampler = new SamplerConfig();
        sampler.setThinkingMode(thinkingMode);
        return chat(toMessages(message, systemPrompt), sampler, 0);
    }

    public CompletableFuture<String> chat(List<ChatMessage> messages) {
        return chat(messages, null, 0);
    }

    public CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
        return requireLibsApi().chat(messages, sampler, maxTokens);
    }

    public CompletableFuture<String> chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options) {
        return requireLibsApi().chat(messages, sampler, maxTokens, options);
    }

    public CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) {
        return requireLibsApi().chatWithUsage(messages, sampler, maxTokens);
    }

    public CompletableFuture<LlmGenerationResult> chatWithUsage(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, InferenceOptions options) {
        return requireLibsApi().chatWithUsage(messages, sampler, maxTokens, options);
    }

    public CompletableFuture<String> chatStream(String message, String systemPrompt, Consumer<String> onToken) {
        return chatStream(toMessages(message, systemPrompt), null, onToken);
    }

    public CompletableFuture<String> chatStream(String message, String systemPrompt, int maxTokens, Consumer<String> onToken) {
        return chatStream(toMessages(message, systemPrompt), null, maxTokens, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, Consumer<String> onToken) {
        return chatStream(messages, null, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) {
        return requireLibsApi().chatStream(messages, sampler, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens, Consumer<String> onToken) {
        return requireLibsApi().chatStream(messages, sampler, maxTokens, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                InferenceOptions options,
                                                Consumer<String> onToken) {
        return requireLibsApi().chatStream(messages, sampler, maxTokens, options, onToken);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                InferenceOptions options,
                                                Consumer<String> onToken,
                                                Consumer<LlmStreamFinish> onFinish) {
        return requireLibsApi().chatStream(messages, sampler, maxTokens, options, onToken, onFinish);
    }

    public CompletableFuture<String> chatStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                InferenceOptions options,
                                                Consumer<String> onToken,
                                                Consumer<String> onThinking,
                                                Consumer<LlmStreamFinish> onFinish) {
        return requireLibsApi().chatStream(messages, sampler, maxTokens, options, onToken, onThinking, onFinish);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible) {
        return requireLibsApi().task(messages, sampler, maxTokens, priority, preemptible);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                          SamplerConfig sampler,
                                          int maxTokens,
                                          int priority,
                                          boolean preemptible,
                                          InferenceOptions options) {
        return requireLibsApi().task(messages, sampler, maxTokens, priority, preemptible, options);
    }

    public CompletableFuture<LlmGenerationResult> taskWithUsage(List<ChatMessage> messages,
                                                                SamplerConfig sampler,
                                                                int maxTokens,
                                                                int priority,
                                                                boolean preemptible,
                                                                InferenceOptions options) {
        return requireLibsApi().taskWithUsage(messages, sampler, maxTokens, priority, preemptible, options);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible,
                                                Consumer<String> tokenConsumer) {
        return requireLibsApi().taskStream(messages, sampler, maxTokens, priority, preemptible, tokenConsumer);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible,
                                                InferenceOptions options,
                                                Consumer<String> tokenConsumer) {
        return requireLibsApi().taskStream(messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible,
                                                InferenceOptions options,
                                                Consumer<String> tokenConsumer,
                                                Consumer<String> thinkingConsumer) {
        return requireLibsApi().taskStream(messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, thinkingConsumer);
    }

    public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages,
                                                                      SamplerConfig sampler,
                                                                      int maxTokens,
                                                                      int priority,
                                                                      boolean preemptible,
                                                                      InferenceOptions options,
                                                                      Consumer<String> tokenConsumer,
                                                                      Consumer<LlmStreamFinish> finishConsumer) {
        return requireLibsApi().taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, finishConsumer);
    }

    public CompletableFuture<LlmGenerationResult> taskStreamWithUsage(List<ChatMessage> messages,
                                                                      SamplerConfig sampler,
                                                                      int maxTokens,
                                                                      int priority,
                                                                      boolean preemptible,
                                                                      InferenceOptions options,
                                                                      Consumer<String> tokenConsumer,
                                                                      Consumer<String> thinkingConsumer,
                                                                      Consumer<LlmStreamFinish> finishConsumer) {
        return requireLibsApi().taskStreamWithUsage(messages, sampler, maxTokens, priority, preemptible, options, tokenConsumer, thinkingConsumer, finishConsumer);
    }

    public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler) {
        return requireLibsApi().countChatPromptTokens(messages, sampler);
    }

    public int countChatPromptTokens(List<ChatMessage> messages, SamplerConfig sampler, InferenceOptions options) {
        return requireLibsApi().countChatPromptTokens(messages, sampler, options);
    }

    public float[] embed(String text) throws Exception {
        return requireLibsApi().embed(text);
    }

    public float[][] embed(List<String> texts) throws Exception {
        return requireLibsApi().embed(texts);
    }

    public List<RagSearchResult> search(String queryText, List<String> texts) {
        return requireLibsApi().search(queryText, texts);
    }

    public List<RagSearchResult> search(String queryText, List<String> texts, int topK, float threshold) {
        return requireLibsApi().search(queryText, texts, topK, threshold);
    }

    /**
     * Whether local chat inference is available: a chat model is configured and loaded, and any
     * configured embedding model is loaded too.
     *
     * <p>This is <em>not</em> a "service is alive" check. A server started in embedding-only mode (for
     * example a cloud-chat deployment that still needs local RAG vectors) is up and serving
     * {@link #embed(String)} and {@link #search} while this method returns {@code false}. Use
     * {@link #isStarted()} for "the service is running", {@link #hasChatEngine()} for chat, and
     * {@link #hasEmbeddingEngine()} for embeddings.
     */
    public boolean isReady() {
        LibsApi api = libsApi;
        return api != null && api.isReady();
    }

    /**
     * Whether {@link #start()} succeeded and {@link #shutdown()} has not run since, regardless of which
     * models are bound. A started embedding-only instance answers {@code true} here while
     * {@link #isReady()} answers {@code false}.
     */
    public boolean isStarted() {
        return libsApi != null;
    }

    /** Whether a chat model is configured and loaded, i.e. whether chat, task, and streaming calls can run. */
    public boolean hasChatEngine() {
        LibsApi api = libsApi;
        return api != null && api.hasChatEngine();
    }

    /** Whether an embedding model is configured and loaded, i.e. whether embedding and RAG search can run. */
    public boolean hasEmbeddingEngine() {
        LibsApi api = libsApi;
        return api != null && api.hasEmbeddingEngine();
    }

    public boolean supportsEnableThinking() {
        ModelRegistry current = models;
        return current != null && current.supportsEnableThinking();
    }

    public boolean supportsMtp() {
        ModelRegistry current = models;
        return current != null && current.supportsMtp();
    }

    public MtpCapability getMtpCapability() {
        ModelRegistry current = models;
        if (current == null) return new MtpCapability(false, 0, null);
        return current.getMtpCapability();
    }

    public LlmRuntimeCapabilities getRuntimeCapabilities() {
        LibsApi api = libsApi;
        return api == null ? LlmRuntimeCapabilities.unavailable() : api.getRuntimeCapabilities();
    }

    public LlmContextBudgetPlan getContextBudgetPlan() {
        LibsApi api = libsApi;
        return api == null ? LlmContextBudgetPlan.unavailable("Model is not loaded") : api.getContextBudgetPlan();
    }

    public LlmContextBudgetPlan getContextBudgetPlan(InferenceLane lane) {
        LibsApi api = libsApi;
        return api == null ? LlmContextBudgetPlan.unavailable("Model is not loaded") : api.getContextBudgetPlan(lane);
    }

    public CompletableFuture<MtpCalibrationResult> calibrateMtpAsync() {
        return calibrateMtpAsync(MtpCalibrationRequest.defaults());
    }

    public CompletableFuture<MtpCalibrationResult> calibrateMtpAsync(MtpCalibrationRequest request) {
        return requireLibsApi().calibrateMtpAsync(request);
    }

    public MtpCalibrationResult calibrateMtp() throws Exception {
        return calibrateMtp(MtpCalibrationRequest.defaults());
    }

    public MtpCalibrationResult calibrateMtp(MtpCalibrationRequest request) throws Exception {
        return requireLibsApi().calibrateMtp(request);
    }

    public boolean hasChatQueueCapacity() {
        LibsApi api = libsApi;
        return api != null && api.hasChatQueueCapacity();
    }

    public boolean hasTaskQueueCapacity() {
        LibsApi api = libsApi;
        return api != null && api.hasTaskQueueCapacity();
    }

    public boolean hasQueueCapacity() {
        return hasChatQueueCapacity();
    }

    /** Number of chat requests waiting in the chat lane; 0 when no chat engine is loaded. */
    public int getChatQueueSize() {
        ModelRegistry current = models;
        if (current == null || !current.hasChatEngine()) return 0;
        return current.getChatEngine().getChatQueueSize();
    }

    private LibsApi requireLibsApi() {
        LibsApi current = libsApi;
        if (current == null) {
            throw new IllegalStateException("Service is not started");
        }
        return current;
    }

    private static List<ChatMessage> toMessages(String message, String systemPrompt) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.add(ChatMessage.user(message));
        return messages;
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) return "unknown";
        int lastSlash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private static ServerConfig copyConfig(ServerConfig source) {
        ServerConfig copy = new ServerConfig();
        copy.modelPath = source.modelPath;
        copy.contextSize = source.contextSize;
        copy.threads = source.threads;
        copy.gpuLayers = source.gpuLayers;
        copy.device = source.device;
        copy.modelAlias = source.modelAlias;
        copy.modelProfile = source.modelProfile;
        copy.mtpDraftModelPath = source.mtpDraftModelPath;
        copy.embeddingModelPath = source.embeddingModelPath;
        copy.embeddingContextSize = source.embeddingContextSize;
        copy.embeddingThreads = source.embeddingThreads;
        copy.embeddingGpuLayers = source.embeddingGpuLayers;
        copy.embeddingDevice = source.embeddingDevice;
        copy.embeddingAlias = source.embeddingAlias;
        copy.maxQueueSize = source.maxQueueSize;
        copy.chatThreads = source.chatThreads;
        copy.chatMaxQueueSize = source.chatMaxQueueSize;
        copy.taskThreads = source.taskThreads;
        copy.flashAttentionMode = source.flashAttentionMode;
        copy.cacheTypeK = source.cacheTypeK;
        copy.cacheTypeV = source.cacheTypeV;
        copy.contextBudgetPolicy = source.contextBudgetPolicy != null ? source.contextBudgetPolicy : LlmContextBudgetPolicy.defaults();
        copy.requestTimeoutSeconds = source.requestTimeoutSeconds;
        copy.inferenceEventListener = source.inferenceEventListener;
        return copy;
    }

    public static class Builder {
        private final ServerConfig config = new ServerConfig();

        public Builder model(String path) {
            config.modelPath = path;
            return this;
        }

        public Builder contextSize(int n) {
            config.contextSize = n;
            return this;
        }

        public Builder chatThreads(int n) {
            config.chatThreads = n;
            config.threads = n;
            return this;
        }

        public Builder chatMaxQueueSize(int n) {
            config.chatMaxQueueSize = n;
            config.maxQueueSize = n;
            return this;
        }

        public Builder gpuLayers(int n) {
            config.gpuLayers = n;
            return this;
        }

        public Builder device(String device) {
            config.device = normalizeDevice(device);
            return this;
        }

        public Builder modelAlias(String name) {
            config.modelAlias = name;
            return this;
        }

        public Builder modelProfile(String name) {
            config.modelProfile = name;
            return this;
        }

        public Builder mtpDraftModel(String path) {
            config.mtpDraftModelPath = path;
            return this;
        }

        public Builder flashAttention(FlashAttentionMode mode) {
            config.flashAttentionMode = mode != null ? mode : FlashAttentionMode.ENABLED;
            return this;
        }

        public Builder cacheTypeK(KvCacheType type) {
            config.cacheTypeK = type;
            return this;
        }

        public Builder cacheTypeV(KvCacheType type) {
            config.cacheTypeV = type;
            return this;
        }

        public Builder contextBudgetPolicy(LlmContextBudgetPolicy policy) {
            config.contextBudgetPolicy = policy != null ? policy : LlmContextBudgetPolicy.defaults();
            return this;
        }

        public Builder embeddingModel(String path) {
            config.embeddingModelPath = path;
            return this;
        }

        public Builder embeddingContextSize(int n) {
            config.embeddingContextSize = n;
            return this;
        }

        public Builder embeddingThreads(int n) {
            config.embeddingThreads = n;
            return this;
        }

        public Builder embeddingGpuLayers(int n) {
            config.embeddingGpuLayers = n;
            return this;
        }

        public Builder embeddingDevice(String device) {
            config.embeddingDevice = normalizeDevice(device);
            return this;
        }

        public Builder embeddingAlias(String name) {
            config.embeddingAlias = name;
            return this;
        }

        public Builder taskThreads(int n) {
            config.taskThreads = n;
            return this;
        }

        public Builder requestTimeoutSeconds(int n) {
            config.requestTimeoutSeconds = n;
            return this;
        }

        public Builder inferenceEventListener(Consumer<InferenceEvent> listener) {
            config.inferenceEventListener = listener;
            return this;
        }

        public JavaLlamaServer build() {
            ServerConfig built = copyConfig(config);
            ServerConfig.validateOrThrow(built);
            return new JavaLlamaServer(built);
        }

        public LlmContextBudgetPlan dryRunContextBudget() throws Exception {
            ServerConfig built = copyConfig(config);
            ServerConfig.validateOrThrow(built);
            return JavaLlamaServer.dryRunContextBudgetInternal(built);
        }

        private static String normalizeDevice(String device) {
            return DeviceSelector.normalize(device);
        }
    }
}
