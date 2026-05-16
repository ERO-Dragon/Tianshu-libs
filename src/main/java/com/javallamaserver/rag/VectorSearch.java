package com.javallamaserver.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class VectorSearch {

    public List<RagSearchResult> search(List<RagChunk> chunks, float[] queryVector, int topK) {
        if (chunks == null || chunks.isEmpty() || queryVector == null || queryVector.length == 0 || topK <= 0) {
            return List.of();
        }
        float[] normalizedQuery = VectorMath.normalizedCopy(queryVector);
        PriorityQueue<RagSearchResult> topResults = new PriorityQueue<>(Comparator.comparingDouble(RagSearchResult::getScore));
        for (RagChunk chunk : chunks) {
            if (chunk == null || chunk.getVector() == null || chunk.getVector().length == 0) continue;
            double score = VectorMath.dot(normalizedQuery, chunk.getVector());
            if (topResults.size() < topK) {
                topResults.offer(new RagSearchResult(chunk, score));
            } else if (score > topResults.peek().getScore()) {
                topResults.poll();
                topResults.offer(new RagSearchResult(chunk, score));
            }
        }
        List<RagSearchResult> results = new ArrayList<>(topResults);
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results;
    }
}
