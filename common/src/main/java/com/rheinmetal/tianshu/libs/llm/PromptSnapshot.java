package com.rheinmetal.tianshu.libs.llm;

record PromptSnapshot(String text, int[] tokenIds) {
    int textLength() {
        return text == null ? 0 : text.length();
    }
}
