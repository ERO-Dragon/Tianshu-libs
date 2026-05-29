package com.rheinmetal.tianshu.libs.rag;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;

public class StaticRagIndex {
    private final EmbeddingEngine embeddingEngine;
    private final RagConfig config;
    private final StaticRagLoader loader;
    private final StaticRagIndexStore indexStore;
    private final VectorSearch vectorSearch;
    private final List<RagChunk> chunks = new ArrayList<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public StaticRagIndex(EmbeddingEngine embeddingEngine, RagConfig config) {
        this.embeddingEngine = embeddingEngine;
        this.config = config;
        this.loader = new StaticRagLoader();
        this.indexStore = new StaticRagIndexStore();
        this.vectorSearch = new VectorSearch();
    }

    public synchronized void load(String path) throws Exception {
        chunks.clear();
        loaded.set(false);
        if (path == null || path.isBlank()) return;
        if (embeddingEngine == null) {
            throw new IllegalStateException("Static RAG requires an embedding engine");
        }
        Path sourcePath = Path.of(path).toAbsolutePath().normalize();
        Path indexRoot = indexStore.resolveIndexDirectory(sourcePath);
        Path indexDirectory = indexStore.defaultIndexDirectory(path, config, embeddingEngine);
        StaticRagIndexStore.IndexDescriptor descriptor = indexStore.describe(path, config, embeddingEngine);
        if (indexStore.canLoad(indexDirectory, descriptor)) {
            chunks.addAll(indexStore.load(indexDirectory));
            loaded.set(true);
            System.out.println("[StaticRagIndex] Loaded chunks from cache: " + chunks.size());
            return;
        }
        List<RagChunk> rebuiltChunks = buildChunks(path, indexRoot);
        chunks.addAll(rebuiltChunks);
        if (!chunks.isEmpty()) {
            indexStore.save(indexDirectory, descriptor, chunks);
            System.out.println("[StaticRagIndex] Saved cache: " + indexDirectory);
        }
        loaded.set(true);
        System.out.println("[StaticRagIndex] Loaded chunks: " + chunks.size());
    }

    public List<RagSearchResult> search(float[] queryVector) {
        if (!loaded.get() || chunks.isEmpty()) return List.of();
        return vectorSearch.search(chunks, queryVector, config.getStaticTopK());
    }

    public boolean isLoaded() {
        return loaded.get();
    }

    public int size() {
        return chunks.size();
    }

    private List<RagChunk> buildChunks(String path, Path excludedRoot) throws Exception {
        List<StaticRagLoader.SourceDocument> documents = loader.load(path, excludedRoot);
        List<StaticRagLoader.SourceDocument> splitDocuments = loader.split(documents, config.getChunkSize(), config.getChunkOverlap());
        if (splitDocuments.isEmpty()) return List.of();
        List<String> texts = splitDocuments.stream().map(StaticRagLoader.SourceDocument::text).toList();
        float[][] vectors = embeddingEngine.embed(texts);
        List<RagChunk> rebuiltChunks = new ArrayList<>(splitDocuments.size());
        for (int i = 0; i < splitDocuments.size(); i++) {
            StaticRagLoader.SourceDocument document = splitDocuments.get(i);
            float[] vector = i < vectors.length ? vectors[i] : null;
            rebuiltChunks.add(new RagChunk("static-" + i, document.text(), document.source(), vector));
        }
        return rebuiltChunks;
    }
}
