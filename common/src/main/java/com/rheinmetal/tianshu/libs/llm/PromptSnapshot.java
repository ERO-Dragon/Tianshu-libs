package com.rheinmetal.tianshu.libs.llm;

record PromptSnapshot(String text, int[] tokenIds, boolean generationStartsInReasoning) {
    int textLength() {
        return text == null ? 0 : text.length();
    }
}
