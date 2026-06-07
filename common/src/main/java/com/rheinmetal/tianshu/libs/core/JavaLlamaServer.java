package com.rheinmetal.tianshu.libs.core;

import com.rheinmetal.tianshu.libs.llm.ChatMessage;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;
import com.rheinmetal.tianshu.libs.llm.InferenceLane;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;
import com.rheinmetal.tianshu.libs.llm.LaneConfig;
import com.rheinmetal.tianshu.libs.llm.LlamaEngine;
import com.rheinmetal.tianshu.libs.llm.ModelRegistry;
import com.rheinmetal.tianshu.libs.llm.SamplerConfig;
import com.rheinmetal.tianshu.libs.nativelib.NativeLibraryLoader;
import com.rheinmetal.tianshu.libs.rag.RagSearchResult;
import org.argeo.jjml.llm.util.ThinkingMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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

    public synchronized void start() throws Exception {
        if (started.get()) return;
        ServerConfig.validateOrThrow(config);
        if (!started.compareAndSet(false, true)) return;

        LlamaEngine engine = null;
        EmbeddingEngine embeddingEngine = null;
        ModelRegistry localModels = null;
        LibsApi localLibsApi = null;
        try {
            String modelAlias = config.modelAlias;
            if (modelAlias == null || modelAlias.isBlank() || modelAlias.equals("unknown")) {
                modelAlias = extractFileName(config.modelPath);
            }

            LaneConfig chatLaneConfig = new LaneConfig(InferenceLane.CHAT, config.chatContext, config.chatThreads, config.chatMaxQueueSize);
            LaneConfig taskLaneConfig = new LaneConfig(InferenceLane.TASK, config.taskContext, config.taskThreads, config.taskMaxQueueSize);
            engine = LlamaEngine.loadChatEngine(
                    config.modelPath,
                    chatLaneConfig,
                    taskLaneConfig,
                    config.gpuLayers,
                    modelAlias,
                    config.modelProfile,
                    config.cacheTypeK,
                    config.cacheTypeV,
                    config.taskSuspendOnChat
            );

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

    public String chat(String message, String systemPrompt) throws Exception {
        return chat(toMessages(message, systemPrompt));
    }

    public String chat(String message, String systemPrompt, ThinkingMode thinkingMode) throws Exception {
        SamplerConfig sampler = new SamplerConfig();
        sampler.setThinkingMode(thinkingMode);
        return chat(toMessages(message, systemPrompt), sampler, 0);
    }

    public String chat(List<ChatMessage> messages) throws Exception {
        return chat(messages, null, 0);
    }

    public String chat(List<ChatMessage> messages, SamplerConfig sampler, int maxTokens) throws Exception {
        return requireLibsApi().chat(messages, sampler, maxTokens);
    }

    public void chatStream(String message, String systemPrompt, Consumer<String> onToken) throws Exception {
        chatStream(toMessages(message, systemPrompt), null, onToken);
    }

    public void chatStream(List<ChatMessage> messages, SamplerConfig sampler, Consumer<String> onToken) throws Exception {
        requireLibsApi().chatStream(messages, sampler, onToken);
    }

    public CompletableFuture<String> task(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible) {
        return requireLibsApi().task(messages, sampler, maxTokens, priority, preemptible);
    }

    public CompletableFuture<String> taskStream(List<ChatMessage> messages,
                                                SamplerConfig sampler,
                                                int maxTokens,
                                                int priority,
                                                boolean preemptible,
                                                Consumer<String> tokenConsumer) {
        return requireLibsApi().taskStream(messages, sampler, maxTokens, priority, preemptible, tokenConsumer);
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

    public boolean isReady() {
        LibsApi api = libsApi;
        return api != null && api.isReady();
    }

    public boolean supportsEnableThinking() {
        ModelRegistry current = models;
        return current != null && current.supportsEnableThinking();
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

    public int getChatQueueSize() {
        ModelRegistry current = models;
        return current == null ? 0 : current.getChatEngine().getChatQueueSize();
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
        copy.modelAlias = source.modelAlias;
        copy.modelProfile = source.modelProfile;
        copy.embeddingModelPath = source.embeddingModelPath;
        copy.embeddingContextSize = source.embeddingContextSize;
        copy.embeddingThreads = source.embeddingThreads;
        copy.embeddingGpuLayers = source.embeddingGpuLayers;
        copy.embeddingAlias = source.embeddingAlias;
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
        return copy;
    }

    public static class Builder {
        private final ServerConfig config = new ServerConfig();
        private boolean taskContextExplicit;

        public Builder model(String path) {
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
            config.modelAlias = name;
            return this;
        }

        public Builder modelProfile(String name) {
            config.modelProfile = name;
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

        public Builder embeddingAlias(String name) {
            config.embeddingAlias = name;
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

        public JavaLlamaServer build() {
            ServerConfig built = copyConfig(config);
            if (!taskContextExplicit) built.taskContext = built.chatContext;
            ServerConfig.validateOrThrow(built);
            return new JavaLlamaServer(built);
        }
    }
}
