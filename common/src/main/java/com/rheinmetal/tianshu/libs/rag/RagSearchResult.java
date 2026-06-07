package com.rheinmetal.tianshu.libs.rag;

public class RagSearchResult {
    public String content;
    public double score;

    public RagSearchResult() {
    }

    public RagSearchResult(String content, double score) {
        this.content = content;
        this.score = score;
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
}