package com.rheinmetal.tianshu.foundry.llm;

record PromptSnapshot(String text, int[] tokenIds, boolean generationStartsInReasoning) {
    int textLength() {
        return text == null ? 0 : text.length();
    }
}
