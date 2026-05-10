package com.javallamaserver.rag;

public class RagSearchResult {
    private final RagChunk chunk;
    private final double score;

    public RagSearchResult(RagChunk chunk, double score) {
        this.chunk = chunk;
        this.score = score;
    }

    public RagChunk getChunk() {
        return chunk;
    }

    public double getScore() {
        return score;
    }
}
