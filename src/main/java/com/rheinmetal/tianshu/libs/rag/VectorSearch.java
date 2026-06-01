package com.rheinmetal.tianshu.libs.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class VectorSearch {

    public List<RagSearchResult> searchWithQuery(float[] queryVector, List<String> texts, float[][] vectors, int topK) {
        if (queryVector == null || queryVector.length == 0 || texts == null || texts.isEmpty() || vectors == null || topK <= 0) {
            return List.of();
        }
        float[] normalizedQuery = VectorMath.normalizedCopy(queryVector);
        PriorityQueue<RagSearchResult> topResults = new PriorityQueue<>(Comparator.comparingDouble(RagSearchResult::getScore));

        for (int i = 0; i < texts.size() && i < vectors.length; i++) {
            String text = texts.get(i);
            float[] vector = vectors[i];
            if (text == null || text.isBlank() || vector == null || vector.length == 0) continue;

            double score = VectorMath.dot(normalizedQuery, VectorMath.normalizedCopy(vector));
            if (topResults.size() < topK) {
                topResults.offer(new RagSearchResult(text, score));
            } else if (score > topResults.peek().getScore()) {
                topResults.poll();
                topResults.offer(new RagSearchResult(text, score));
            }
        }

        List<RagSearchResult> results = new ArrayList<>(topResults);
        results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return results;
    }
}
