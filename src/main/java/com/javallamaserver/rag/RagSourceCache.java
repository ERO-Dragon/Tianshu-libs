package com.javallamaserver.rag;

import com.javallamaserver.llm.EmbeddingEngine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RagSourceCache {
    private final EmbeddingEngine embeddingEngine;
    private final RagConfig config;
    private final long memoryRefreshIntervalMillis;
    private final Map<Path, StaticRagIndex> staticIndexes = new HashMap<>();
    private final Map<Path, MemoryRagIndex> memoryIndexes = new HashMap<>();

    public RagSourceCache(EmbeddingEngine embeddingEngine, RagConfig config, long memoryRefreshIntervalMillis) {
        this.embeddingEngine = embeddingEngine;
        this.config = config;
        this.memoryRefreshIntervalMillis = memoryRefreshIntervalMillis;
    }

    public synchronized StaticRagIndex getStaticIndex(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        StaticRagIndex existing = staticIndexes.get(normalized);
        if (existing != null) return existing;
        StaticRagIndex created = new StaticRagIndex(embeddingEngine, config);
        if (Files.exists(normalized)) created.load(normalized.toString());
        staticIndexes.put(normalized, created);
        return created;
    }

    public synchronized MemoryRagIndex getMemoryIndex(Path path) throws Exception {
        Path normalized = path.toAbsolutePath().normalize();
        MemoryRagIndex existing = memoryIndexes.get(normalized);
        if (existing != null) return existing;
        MemoryRagIndex created = new MemoryRagIndex(embeddingEngine, normalized.toString(), memoryRefreshIntervalMillis);
        created.load();
        memoryIndexes.put(normalized, created);
        return created;
    }

    public List<RagSearchResult> searchStatic(Path path, float[] queryVector) throws Exception {
        if (path == null || !Files.exists(path)) return List.of();
        return getStaticIndex(path).search(queryVector);
    }

    public List<RagSearchResult> searchMemory(Path path, float[] queryVector, int tokenBudget) throws Exception {
        if (path == null) return List.of();
        return getMemoryIndex(path).search(queryVector, tokenBudget);
    }

    public synchronized int staticChunkCount() {
        int count = 0;
        for (StaticRagIndex index : staticIndexes.values()) count += index.size();
        return count;
    }

    public synchronized int memoryCount() {
        int count = 0;
        for (MemoryRagIndex index : memoryIndexes.values()) count += index.size();
        return count;
    }
}
