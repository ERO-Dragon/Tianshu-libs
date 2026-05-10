package com.javallamaserver.rag;

import com.javallamaserver.llm.EmbeddingEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class StaticRagIndex {
    private final EmbeddingEngine embeddingEngine;
    private final RagConfig config;
    private final StaticRagLoader loader;
    private final VectorSearch vectorSearch;
    private final List<RagChunk> chunks = new ArrayList<>();
    private final AtomicBoolean loaded = new AtomicBoolean(false);

    public StaticRagIndex(EmbeddingEngine embeddingEngine, RagConfig config) {
        this.embeddingEngine = embeddingEngine;
        this.config = config;
        this.loader = new StaticRagLoader();
        this.vectorSearch = new VectorSearch();
    }

    public synchronized void load(String path) throws Exception {
        chunks.clear();
        loaded.set(false);
        if (path == null || path.isBlank()) return;
        if (embeddingEngine == null) {
            throw new IllegalStateException("Static RAG requires an embedding engine");
        }
        List<StaticRagLoader.SourceDocument> documents = loader.load(path);
        List<StaticRagLoader.SourceDocument> splitDocuments = loader.split(documents, config.getChunkSize(), config.getChunkOverlap());
        if (splitDocuments.isEmpty()) {
            loaded.set(true);
            return;
        }
        List<String> texts = splitDocuments.stream().map(StaticRagLoader.SourceDocument::text).toList();
        float[][] vectors = embeddingEngine.embed(texts);
        for (int i = 0; i < splitDocuments.size(); i++) {
            StaticRagLoader.SourceDocument document = splitDocuments.get(i);
            float[] vector = i < vectors.length ? vectors[i] : null;
            chunks.add(new RagChunk("static-" + i, document.text(), document.source(), vector));
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
}
