package com.rheinmetal.tianshu.libs.core;

import java.util.ArrayList;
import java.util.List;

import java.util.function.Consumer;

import com.rheinmetal.tianshu.libs.llm.InferenceEvent;
import com.rheinmetal.tianshu.libs.llm.KvCacheType;

public class ServerConfig {
    public static final int DEFAULT_CONTEXT_SIZE = 16000;
    public static final int DEFAULT_THREAD_COUNT = Runtime.getRuntime().availableProcessors();
    public static final int DEFAULT_GPU_LAYERS = 999;
    public static final int DEFAULT_MAX_QUEUE_SIZE = 4;
    public static final int TASK_HOT_SUSPEND_SLOTS = 0;
    public static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 300;

    public String modelPath;
    public int contextSize = DEFAULT_CONTEXT_SIZE;
    public int threads = DEFAULT_THREAD_COUNT;
    public int gpuLayers = DEFAULT_GPU_LAYERS;
    public String device;
    public String modelAlias = "unknown";
    public String modelProfile;
    public String embeddingModelPath;
    public int embeddingContextSize = DEFAULT_CONTEXT_SIZE;
    public int embeddingThreads = DEFAULT_THREAD_COUNT;
    public int embeddingGpuLayers = DEFAULT_GPU_LAYERS;
    public String embeddingDevice;
    public String embeddingAlias = "embedding";
    public int maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
    public int chatThreads = DEFAULT_THREAD_COUNT;
    public int chatMaxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
    public int taskThreads = Math.max(1, Math.min(2, DEFAULT_THREAD_COUNT));
    public boolean taskSuspendOnChat = true;
    public KvCacheType cacheTypeK;
    public KvCacheType cacheTypeV;
    public int requestTimeoutSeconds = DEFAULT_REQUEST_TIMEOUT_SECONDS;
    public Consumer<InferenceEvent> inferenceEventListener;

    static void validateOrThrow(ServerConfig config) {
        List<String> errors = new ArrayList<>();
        validate(config, errors);
        if (!errors.isEmpty()) {
            throw new ConfigException(String.join(System.lineSeparator(), errors));
        }
    }

    private static void validate(ServerConfig config, List<String> errors) {
        if (config.modelPath == null || config.modelPath.isBlank()) errors.add("modelPath is required");
        range(config.contextSize, 512, 262144, "contextSize", errors);
        range(config.embeddingContextSize, 512, 262144, "embeddingContextSize", errors);
        range(config.threads, 1, 512, "threads", errors);
        range(config.chatThreads, 1, 512, "chatThreads", errors);
        range(config.taskThreads, 1, 512, "taskThreads", errors);
        range(config.embeddingThreads, 1, 512, "embeddingThreads", errors);
        range(config.gpuLayers, 0, 9999, "gpuLayers", errors);
        range(config.embeddingGpuLayers, 0, 9999, "embeddingGpuLayers", errors);
        range(config.maxQueueSize, 1, 1024, "maxQueueSize", errors);
        range(config.chatMaxQueueSize, 1, 1024, "chatMaxQueueSize", errors);
        range(config.requestTimeoutSeconds, 1, 3600, "requestTimeoutSeconds", errors);
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
