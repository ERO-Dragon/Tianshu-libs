package com.javallamaserver.rag;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.javallamaserver.llm.EmbeddingEngine;

import java.util.ArrayList;
import java.util.List;

public class DynamicRagRetriever {
    private final EmbeddingEngine embeddingEngine;
    private final RagConfig config;
    private final VectorSearch vectorSearch;

    public DynamicRagRetriever(EmbeddingEngine embeddingEngine, RagConfig config) {
        this.embeddingEngine = embeddingEngine;
        this.config = config;
        this.vectorSearch = new VectorSearch();
    }

    public List<RagSearchResult> search(List<JsonElement> entries, float[] queryVector) throws Exception {
        if (entries == null || entries.isEmpty() || queryVector == null || queryVector.length == 0 || config.getDynamicTopK() <= 0) {
            return List.of();
        }
        if (embeddingEngine == null) {
            throw new IllegalStateException("Dynamic RAG requires an embedding engine");
        }
        List<String> texts = new ArrayList<>();
        for (JsonElement entry : entries) {
            String text = extractText(entry);
            if (!text.isBlank()) texts.add(text);
        }
        if (texts.isEmpty()) return List.of();
        float[][] vectors = embeddingEngine.embed(texts);
        List<RagChunk> chunks = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] vector = i < vectors.length ? vectors[i] : null;
            chunks.add(new RagChunk("dynamic-" + i, texts.get(i), "dynamic", vector));
        }
        return vectorSearch.search(chunks, queryVector, config.getDynamicTopK());
    }

    private String extractText(JsonElement entry) {
        if (entry == null || entry.isJsonNull()) return "";
        if (entry.isJsonPrimitive()) return entry.getAsString().trim();
        if (entry.isJsonObject()) {
            JsonObject object = entry.getAsJsonObject();
            if (object.has("text") && !object.get("text").isJsonNull()) {
                return object.get("text").getAsString().trim();
            }
            if (object.has("content") && !object.get("content").isJsonNull()) {
                return object.get("content").getAsString().trim();
            }
            return object.toString();
        }
        return entry.toString();
    }
}
