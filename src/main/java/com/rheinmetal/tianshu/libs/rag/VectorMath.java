package com.rheinmetal.tianshu.libs.rag;

public final class VectorMath {
    private VectorMath() {
    }

    public static float[] normalizedCopy(float[] vector) {
        if (vector == null || vector.length == 0) return vector;
        double norm = 0.0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) return vector.clone();
        double scale = 1.0 / Math.sqrt(norm);
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] * scale);
        }
        return normalized;
    }

    public static double dot(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0.0;
        int length = Math.min(a.length, b.length);
        double score = 0.0;
        for (int i = 0; i < length; i++) {
            score += a[i] * b[i];
        }
        return score;
    }
}
