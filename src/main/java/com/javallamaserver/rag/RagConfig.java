package com.javallamaserver.rag;

public class RagConfig {
    private final int staticTopK;
    private final int dynamicTopK;
    private final int chunkSize;
    private final int chunkOverlap;

    public RagConfig(int staticTopK, int dynamicTopK, int chunkSize, int chunkOverlap) {
        this.staticTopK = Math.max(0, staticTopK);
        this.dynamicTopK = Math.max(0, dynamicTopK);
        this.chunkSize = Math.max(200, chunkSize);
        this.chunkOverlap = Math.max(0, Math.min(chunkOverlap, this.chunkSize / 2));
    }

    public static RagConfig defaults() {
        return new RagConfig(4, 4, 900, 120);
    }

    public int getStaticTopK() {
        return staticTopK;
    }

    public int getDynamicTopK() {
        return dynamicTopK;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public int getChunkOverlap() {
        return chunkOverlap;
    }
}
