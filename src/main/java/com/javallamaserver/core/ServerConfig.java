package com.javallamaserver.core;

import com.javallamaserver.llm.KvCacheType;

import java.util.ArrayList;
import java.util.List;

public class ServerConfig {
    public static final int DEFAULT_CONTEXT_SIZE = 16000;
    public static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    public static final int DEFAULT_GPU_LAYERS = 999;
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7158;
    public static final int DEFAULT_STATIC_RAG_TOP_K = 4;
    public static final int DEFAULT_DYNAMIC_RAG_TOP_K = 4;
    public static final int DEFAULT_RAG_CHUNK_SIZE = 900;
    public static final int DEFAULT_RAG_CHUNK_OVERLAP = 120;
    public static final int DEFAULT_MAX_QUEUE_SIZE = 4;
    public static final int DEFAULT_TASK_MAX_QUEUE_SIZE = 1;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;

    public String modelPath;
    public int port = DEFAULT_PORT;
    public String host = DEFAULT_HOST;
    public int contextSize = DEFAULT_CONTEXT_SIZE;
    public int threads = DEFAULT_THREAD_COUNT;
    public int gpuLayers = DEFAULT_GPU_LAYERS;
    public String alias = "unknown";
    public String modelProfile;
    public String embeddingModelPath;
    public int embeddingContextSize = DEFAULT_CONTEXT_SIZE;
    public int embeddingThreads = DEFAULT_THREAD_COUNT;
    public int embeddingGpuLayers = DEFAULT_GPU_LAYERS;
    public String embeddingAlias = "embedding";
    public String staticRagPath;
    public String memoryRagPath;
    public String ragRootPath;
    public int ragProfileRefreshIntervalMillis = 1000;
    public int worldStaticRagScanIntervalMillis = 5000;
    public int memoryRagRefreshIntervalMillis = 1000;
    public int staticRagTopK = DEFAULT_STATIC_RAG_TOP_K;
    public int dynamicRagTopK = DEFAULT_DYNAMIC_RAG_TOP_K;
    public int ragChunkSize = DEFAULT_RAG_CHUNK_SIZE;
    public int ragChunkOverlap = DEFAULT_RAG_CHUNK_OVERLAP;
    public int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
    public int chatContext = DEFAULT_CONTEXT_SIZE;
    public int chatThreads = DEFAULT_THREAD_COUNT;
    public int chatMaxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
    public int taskContext = DEFAULT_CONTEXT_SIZE;
    public int taskThreads = Math.max(1, Math.min(2, DEFAULT_THREAD_COUNT));
    public int taskMaxQueueSize = DEFAULT_TASK_MAX_QUEUE_SIZE;
    public boolean taskSuspendOnChat = true;
    public KvCacheType cacheTypeK;
    public KvCacheType cacheTypeV;
    public int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    public boolean help;

    public static ServerConfig parse(String[] args) {
        ServerConfig config = new ServerConfig();
        boolean chatContextSet = false;
        boolean chatThreadsSet = false;
        boolean chatMaxQueueSet = false;
        boolean taskContextSet = false;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            try {
                switch (arg) {
                    case "-m", "--model" -> config.modelPath = value(args, ++i, arg);
                    case "-c", "--context" -> config.contextSize = intValue(args, ++i, arg);
                    case "-t", "--threads" -> config.threads = intValue(args, ++i, arg);
                    case "--host" -> config.host = value(args, ++i, arg);
                    case "--port" -> config.port = intValue(args, ++i, arg);
                    case "--alias" -> config.alias = value(args, ++i, arg);
                    case "--model-profile" -> config.modelProfile = value(args, ++i, arg);
                    case "--embedding-model" -> config.embeddingModelPath = value(args, ++i, arg);
                    case "--embedding-context" -> config.embeddingContextSize = intValue(args, ++i, arg);
                    case "--embedding-threads" -> config.embeddingThreads = intValue(args, ++i, arg);
                    case "--embedding-gpu-layers" -> config.embeddingGpuLayers = intValue(args, ++i, arg);
                    case "--embedding-alias" -> config.embeddingAlias = value(args, ++i, arg);
                    case "--static-rag-path" -> config.staticRagPath = value(args, ++i, arg);
                    case "--memory-rag-path" -> config.memoryRagPath = value(args, ++i, arg);
                    case "--rag-root-path" -> config.ragRootPath = value(args, ++i, arg);
                    case "--rag-profile-refresh-interval-ms" -> config.ragProfileRefreshIntervalMillis = intValue(args, ++i, arg);
                    case "--world-static-rag-scan-interval-ms" -> config.worldStaticRagScanIntervalMillis = intValue(args, ++i, arg);
                    case "--memory-rag-refresh-interval-ms" -> config.memoryRagRefreshIntervalMillis = intValue(args, ++i, arg);
                    case "--static-rag-top-k" -> config.staticRagTopK = intValue(args, ++i, arg);
                    case "--dynamic-rag-top-k" -> config.dynamicRagTopK = intValue(args, ++i, arg);
                    case "--rag-chunk-size" -> config.ragChunkSize = intValue(args, ++i, arg);
                    case "--rag-chunk-overlap" -> config.ragChunkOverlap = intValue(args, ++i, arg);
                    case "--max-queue-size" -> config.maxQueueSize = intValue(args, ++i, arg);
                    case "--chat-context" -> { config.chatContext = intValue(args, ++i, arg); chatContextSet = true; }
                    case "--chat-threads" -> { config.chatThreads = intValue(args, ++i, arg); chatThreadsSet = true; }
                    case "--chat-max-queue-size" -> { config.chatMaxQueueSize = intValue(args, ++i, arg); chatMaxQueueSet = true; }
                    case "--task-context" -> { config.taskContext = intValue(args, ++i, arg); taskContextSet = true; }
                    case "--task-threads" -> config.taskThreads = intValue(args, ++i, arg);
                    case "--task-max-queue-size" -> config.taskMaxQueueSize = intValue(args, ++i, arg);
                    case "--task-suspend-on-chat" -> config.taskSuspendOnChat = booleanValue(args, ++i, arg);
                    case "--cache-type-k" -> config.cacheTypeK = KvCacheType.parse(value(args, ++i, arg));
                    case "--cache-type-v" -> config.cacheTypeV = KvCacheType.parse(value(args, ++i, arg));
                    case "--request-timeout-seconds" -> config.requestTimeoutSeconds = intValue(args, ++i, arg);
                    case "-ngl", "--n-gpu-layers" -> config.gpuLayers = intValue(args, ++i, arg);
                    case "-h", "--help" -> config.help = true;
                    default -> errors.add("Unknown option: " + arg);
                }
            } catch (IllegalArgumentException e) {
                errors.add(e.getMessage());
            }
        }
        if (!chatContextSet) config.chatContext = config.contextSize;
        if (!chatThreadsSet) config.chatThreads = config.threads;
        if (!chatMaxQueueSet) config.chatMaxQueueSize = config.maxQueueSize;
        if (!taskContextSet) config.taskContext = config.contextSize;
        if (!config.help) {
            validate(config, errors);
        }
        if (!errors.isEmpty()) {
            throw new ConfigException(String.join(System.lineSeparator(), errors));
        }
        return config;
    }

    static void validateOrThrow(ServerConfig config) {
        List<String> errors = new ArrayList<>();
        validate(config, errors);
        if (!errors.isEmpty()) {
            throw new ConfigException(String.join(System.lineSeparator(), errors));
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("-")) {
            throw new IllegalArgumentException("Missing value for option: " + option);
        }
        return args[index];
    }

    private static int intValue(String[] args, int index, String option) {
        String value = value(args, index, option);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer for option " + option + ": " + value);
        }
    }

    private static boolean booleanValue(String[] args, int index, String option) {
        String value = value(args, index, option);
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("Invalid boolean for option " + option + ": " + value);
    }

    private static void validate(ServerConfig config, List<String> errors) {
        if (config.modelPath == null || config.modelPath.isBlank()) errors.add("Missing required option: --model <path>");
        if (!DEFAULT_HOST.equals(config.host)) errors.add("Unsafe host: only 127.0.0.1 is allowed");
        range(config.port, 1, 65535, "--port", errors);
        range(config.contextSize, 512, 262144, "--context", errors);
        range(config.chatContext, 512, 262144, "--chat-context", errors);
        range(config.taskContext, 512, 262144, "--task-context", errors);
        range(config.embeddingContextSize, 512, 262144, "--embedding-context", errors);
        range(config.threads, 1, 512, "--threads", errors);
        range(config.chatThreads, 1, 512, "--chat-threads", errors);
        range(config.taskThreads, 1, 512, "--task-threads", errors);
        range(config.embeddingThreads, 1, 512, "--embedding-threads", errors);
        range(config.gpuLayers, 0, 9999, "--n-gpu-layers", errors);
        range(config.embeddingGpuLayers, 0, 9999, "--embedding-gpu-layers", errors);
        range(config.staticRagTopK, 0, 64, "--static-rag-top-k", errors);
        range(config.ragProfileRefreshIntervalMillis, 0, 60000, "--rag-profile-refresh-interval-ms", errors);
        range(config.worldStaticRagScanIntervalMillis, 0, 60000, "--world-static-rag-scan-interval-ms", errors);
        range(config.memoryRagRefreshIntervalMillis, 0, 60000, "--memory-rag-refresh-interval-ms", errors);
        range(config.dynamicRagTopK, 0, 64, "--dynamic-rag-top-k", errors);
        range(config.ragChunkSize, 200, 20000, "--rag-chunk-size", errors);
        range(config.ragChunkOverlap, 0, Math.max(0, config.ragChunkSize / 2), "--rag-chunk-overlap", errors);
        range(config.maxQueueSize, 1, 1024, "--max-queue-size", errors);
        range(config.chatMaxQueueSize, 1, 1024, "--chat-max-queue-size", errors);
        range(config.taskMaxQueueSize, 1, 1024, "--task-max-queue-size", errors);
        range(config.requestTimeoutSeconds, 1, 3600, "--request-timeout-seconds", errors);
        if (config.staticRagPath != null && !config.staticRagPath.isBlank() && (config.embeddingModelPath == null || config.embeddingModelPath.isBlank())) {
            errors.add("Static RAG requires --embedding-model");
        }
        if (config.memoryRagPath != null && !config.memoryRagPath.isBlank() && (config.embeddingModelPath == null || config.embeddingModelPath.isBlank())) {
            errors.add("Memory RAG requires --embedding-model");
        }
        if (config.ragRootPath != null && !config.ragRootPath.isBlank() && (config.embeddingModelPath == null || config.embeddingModelPath.isBlank())) {
            errors.add("Profile RAG requires --embedding-model");
        }
    }

    private static void range(int value, int min, int max, String option, List<String> errors) {
        if (value < min || value > max) {
            errors.add(option + " must be between " + min + " and " + max + ", got " + value);
        }
    }

    public static class ConfigException extends RuntimeException {
        public ConfigException(String message) {
            super(message);
        }
    }
}
