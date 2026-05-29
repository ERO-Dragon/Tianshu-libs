package com.javallamaserver.core;

import com.javallamaserver.llm.EmbeddingEngine;
import com.javallamaserver.llm.InferenceLane;
import com.javallamaserver.llm.LaneConfig;
import com.javallamaserver.llm.LlamaEngine;
import com.javallamaserver.llm.ModelRegistry;
import com.javallamaserver.llm.SamplerConfig;
import com.javallamaserver.nativelib.NativeLibraryLoader;
import com.javallamaserver.rag.RagService;
import com.javallamaserver.web.ChatController;
import com.javallamaserver.web.ChatController.ChatMessage;
import com.javallamaserver.web.ChatController.ChatRequest;
import io.javalin.Javalin;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class LlamaServerService {
    static {
        NativeLibraryLoader.ensureLoaded();
    }

    private final ServerConfig config;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile ModelRegistry models;
    private volatile RagService ragService;
    private volatile ChatController chatController;
    private volatile Javalin app;

    private LlamaServerService(ServerConfig config) {
        this.config = copyConfig(config);
    }

    public static Builder builder() {
        return new Builder();
    }

    public synchronized void start() throws Exception {
        if (started.get()) return;
        ServerConfig.validateOrThrow(config);
        if (!started.compareAndSet(false, true)) return;

        LlamaEngine engine = null;
        EmbeddingEngine embeddingEngine = null;
        ModelRegistry localModels = null;
        try {
            String alias = config.alias;
            if (alias == null || alias.isBlank() || alias.equals("unknown")) {
                alias = ServerApp.extractFileName(config.modelPath);
            }

            LaneConfig chatLaneConfig = new LaneConfig(InferenceLane.CHAT, config.chatContext, config.chatThreads, config.chatMaxQueueSize);
            LaneConfig taskLaneConfig = new LaneConfig(InferenceLane.TASK, config.taskContext, config.taskThreads, config.taskMaxQueueSize);
            engine = LlamaEngine.loadChatEngine(
                    config.modelPath,
                    chatLaneConfig,
                    taskLaneConfig,
                    config.gpuLayers,
                    alias,
                    config.modelProfile,
                    config.cacheTypeK,
                    config.cacheTypeV,
                    config.taskSuspendOnChat
            );

            if (config.embeddingModelPath != null && !config.embeddingModelPath.isBlank()) {
                String embeddingAlias = config.embeddingAlias;
                if (embeddingAlias == null || embeddingAlias.isBlank() || embeddingAlias.equals("embedding")) {
                    embeddingAlias = ServerApp.extractFileName(config.embeddingModelPath);
                }
                embeddingEngine = EmbeddingEngine.load(
                        config.embeddingModelPath,
                        config.embeddingContextSize,
                        config.embeddingThreads,
                        config.embeddingGpuLayers,
                        embeddingAlias
                );
            }

            localModels = new ModelRegistry(engine, embeddingEngine);
            RagService localRagService = ServerApp.buildRagService(config, localModels);
            ChatController localChatController = new ChatController(localModels.getChatEngine(), embeddingEngine, localRagService, config.requestTimeoutSeconds);

            this.models = localModels;
            this.ragService = localRagService;
            this.chatController = localChatController;
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

    public synchronized void startWithHttp(int port) throws Exception {
        config.port = port;
        start();
        if (app != null) return;
        this.app = ServerApp.startHttpServer(config, requireModels(), ragService, requireChatController());
    }

    public synchronized void shutdown() {
        Javalin currentApp = this.app;
        this.app = null;
        if (currentApp != null) {
            try {
                currentApp.stop();
            } catch (Exception ignored) {
            }
        }
        ModelRegistry currentModels = this.models;
        this.models = null;
        this.ragService = null;
        this.chatController = null;
        if (currentModels != null) {
            try {
                currentModels.shutdown();
            } catch (Exception ignored) {
            }
        }
        started.set(false);
    }

    public String chatSync(String message, String systemPrompt) throws Exception {
        return chatSync(toMessages(message, systemPrompt));
    }

    public String chatSync(List<ChatMessage> messages) throws Exception {
        return chatSync(messages, null, 0);
    }

    public String chatSync(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        ChatRequest request = new ChatRequest();
        request.messages = messages;
        request.max_tokens = maxTokens > 0 ? maxTokens : null;
        request.lane = InferenceLane.CHAT.wireName();
        return requireChatController().executeSyncChat(request, sampler);
    }

    public void chatStream(String message, String systemPrompt, Consumer<String> onToken) throws Exception {
        chatStream(toMessages(message, systemPrompt), null, onToken);
    }

    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        ChatRequest request = new ChatRequest();
        request.messages = messages;
        request.lane = InferenceLane.CHAT.wireName();
        requireChatController().executeStreamChat(request, sampler, onToken)
                .get(config.requestTimeoutSeconds, TimeUnit.SECONDS);
    }

    public CompletableFuture<String> submitTask(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible) {
        ChatRequest request = new ChatRequest();
        request.messages = messages;
        request.max_tokens = maxTokens > 0 ? maxTokens : null;
        request.task_priority = priority;
        request.task_preemptible = preemptible;
        request.lane = InferenceLane.TASK.wireName();
        return requireChatController().executeAsyncChat(request, sampler);
    }

    public boolean isReady() {
        return models != null && models.isReady();
    }

    public boolean hasChatQueueCapacity() {
        return models != null && models.getChatEngine().hasQueueCapacity();
    }

    public boolean hasQueueCapacity() {
        return hasChatQueueCapacity();
    }

    public int getChatQueueSize() {
        return models == null ? 0 : models.getChatEngine().getChatQueueSize();
    }

    private ModelRegistry requireModels() {
        ModelRegistry current = models;
        if (current == null) {
            throw new IllegalStateException("Service is not started");
        }
        return current;
    }

    private ChatController requireChatController() {
        ChatController current = chatController;
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
            messages.add(new ChatMessage("system", systemPrompt));
        }
        messages.add(new ChatMessage("user", message));
        return messages;
    }

    private static ServerConfig copyConfig(ServerConfig source) {
        ServerConfig copy = new ServerConfig();
        copy.modelPath = source.modelPath;
        copy.port = source.port;
        copy.host = source.host;
        copy.contextSize = source.contextSize;
        copy.threads = source.threads;
        copy.gpuLayers = source.gpuLayers;
        copy.alias = source.alias;
        copy.modelProfile = source.modelProfile;
        copy.embeddingModelPath = source.embeddingModelPath;
        copy.embeddingContextSize = source.embeddingContextSize;
        copy.embeddingThreads = source.embeddingThreads;
        copy.embeddingGpuLayers = source.embeddingGpuLayers;
        copy.embeddingAlias = source.embeddingAlias;
        copy.staticRagPath = source.staticRagPath;
        copy.memoryRagPath = source.memoryRagPath;
        copy.ragRootPath = source.ragRootPath;
        copy.ragProfileRefreshIntervalMillis = source.ragProfileRefreshIntervalMillis;
        copy.worldStaticRagScanIntervalMillis = source.worldStaticRagScanIntervalMillis;
        copy.memoryRagRefreshIntervalMillis = source.memoryRagRefreshIntervalMillis;
        copy.staticRagTopK = source.staticRagTopK;
        copy.dynamicRagTopK = source.dynamicRagTopK;
        copy.ragChunkSize = source.ragChunkSize;
        copy.ragChunkOverlap = source.ragChunkOverlap;
        copy.maxQueueSize = source.maxQueueSize;
        copy.chatContext = source.chatContext;
        copy.chatThreads = source.chatThreads;
        copy.chatMaxQueueSize = source.chatMaxQueueSize;
        copy.taskContext = source.taskContext;
        copy.taskThreads = source.taskThreads;
        copy.taskMaxQueueSize = source.taskMaxQueueSize;
        copy.taskSuspendOnChat = source.taskSuspendOnChat;
        copy.cacheTypeK = source.cacheTypeK;
        copy.cacheTypeV = source.cacheTypeV;
        copy.requestTimeoutSeconds = source.requestTimeoutSeconds;
        copy.help = source.help;
        return copy;
    }

    public static class Builder {
        private final ServerConfig config = new ServerConfig();
        private boolean taskContextExplicit;

        public Builder chatModel(String path) {
            config.modelPath = path;
            return this;
        }

        public Builder chatContext(int n) {
            config.chatContext = n;
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

        public Builder modelAlias(String name) {
            config.alias = name;
            return this;
        }

        public Builder modelProfile(String name) {
            config.modelProfile = name;
            return this;
        }

        public Builder cacheTypeK(com.javallamaserver.llm.KvCacheType type) {
            config.cacheTypeK = type;
            return this;
        }

        public Builder cacheTypeV(com.javallamaserver.llm.KvCacheType type) {
            config.cacheTypeV = type;
            return this;
        }

        public Builder embeddingModel(String path) {
            config.embeddingModelPath = path;
            return this;
        }

        public Builder embeddingContext(int n) {
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

        public Builder embeddingAlias(String name) {
            config.embeddingAlias = name;
            return this;
        }

        public Builder staticRagPath(String path) {
            config.staticRagPath = path;
            return this;
        }

        public Builder memoryRagPath(String path) {
            config.memoryRagPath = path;
            return this;
        }

        public Builder ragRootPath(String path) {
            config.ragRootPath = path;
            return this;
        }

        public Builder ragChunkSize(int n) {
            config.ragChunkSize = n;
            return this;
        }

        public Builder ragChunkOverlap(int n) {
            config.ragChunkOverlap = n;
            return this;
        }

        public Builder staticRagTopK(int n) {
            config.staticRagTopK = n;
            return this;
        }

        public Builder dynamicRagTopK(int n) {
            config.dynamicRagTopK = n;
            return this;
        }

        public Builder taskContext(int n) {
            config.taskContext = n;
            taskContextExplicit = true;
            return this;
        }

        public Builder taskThreads(int n) {
            config.taskThreads = n;
            return this;
        }

        public Builder taskMaxQueueSize(int n) {
            config.taskMaxQueueSize = n;
            return this;
        }

        public Builder taskSuspendOnChat(boolean value) {
            config.taskSuspendOnChat = value;
            return this;
        }

        public Builder requestTimeoutSeconds(int n) {
            config.requestTimeoutSeconds = n;
            return this;
        }

        public LlamaServerService build() {
            ServerConfig built = copyConfig(config);
            if (!taskContextExplicit) built.taskContext = built.chatContext;
            ServerConfig.validateOrThrow(built);
            return new LlamaServerService(built);
        }
    }
}
