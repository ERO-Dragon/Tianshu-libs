package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.ggml.params.GgmlType;

public enum KvCacheType {
    F16("f16", GgmlType.GGML_TYPE_F16),
    Q8_0("q8_0", GgmlType.GGML_TYPE_Q8_0);

    private final String wireName;
    private final GgmlType ggmlType;

    KvCacheType(String wireName, GgmlType ggmlType) {
        this.wireName = wireName;
        this.ggmlType = ggmlType;
    }

    public GgmlType getGgmlType() {
        return ggmlType;
    }

    public String wireName() {
        return wireName;
    }

    public static KvCacheType parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        for (KvCacheType type : values()) {
            if (type.wireName.equals(normalized)) return type;
        }
        throw new IllegalArgumentException("Unsupported KV cache type: " + value);
    }
}
