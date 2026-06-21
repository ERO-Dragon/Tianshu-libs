package com.rheinmetal.tianshu.libs.llm;

import java.nio.IntBuffer;
import java.util.List;

final class TokenIds {
    private TokenIds() {
    }

    static int[] copyRemaining(IntBuffer tokens) {
        int[] copy = new int[tokens.remaining()];
        tokens.get(copy);
        return copy;
    }

    static int[] concat(int[] first, List<Integer> second) {
        int[] left = first == null ? new int[0] : first;
        int rightSize = second == null ? 0 : second.size();
        int[] result = new int[left.length + rightSize];
        System.arraycopy(left, 0, result, 0, left.length);
        for (int i = 0; i < rightSize; i++) {
            result[left.length + i] = second.get(i);
        }
        return result;
    }

    static int[] tail(List<Integer> tokens, int fromIndex) {
        if (tokens == null || tokens.isEmpty()) return new int[0];
        int start = Math.max(0, Math.min(fromIndex, tokens.size()));
        int[] result = new int[tokens.size() - start];
        for (int i = start; i < tokens.size(); i++) {
            result[i - start] = tokens.get(i);
        }
        return result;
    }
}
