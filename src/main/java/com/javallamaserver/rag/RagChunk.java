package com.javallamaserver.rag;

public class RagChunk {
    private final String id;
    private final String text;
    private final String source;
    private final float[] vector;

    public RagChunk(String id, String text, String source, float[] vector) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("RAG chunk text is required");
        }
        this.id = id == null || id.isBlank() ? source : id;
        this.text = text.trim();
        this.source = source == null ? "unknown" : source;
        this.vector = VectorMath.normalizedCopy(vector);
    }

    public String getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public String getSource() {
        return source;
    }

    public float[] getVector() {
        return vector;
    }
}
