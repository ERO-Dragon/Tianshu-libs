package com.javallamaserver.rag;

import java.util.ArrayList;
import java.util.List;

public class VectorSearch {

    public List<RagSearchResult> search(List<RagChunk> chunks, float[] queryVector, int topK) {
        if (chunks == null || chunks.isEmpty() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        List<RagSearchResult> results = new ArrayList<>();
        for (RagChunk chunk : chunks) {
            if (chunk == null || chunk.getVector() == null || chunk.getVector().length == 0) continue;
            results.add(new RagSearchResult(chunk, cosine(queryVector, chunk.getVector())));
        }
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        if (results.size() <= topK) return results;
        return new ArrayList<>(results.subList(0, topK));
    }

    private double cosine(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
