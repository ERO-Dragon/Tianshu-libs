package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.params.FlashAttentionType;

public enum FlashAttentionMode {
    AUTO("auto", FlashAttentionType.LLAMA_FLASH_ATTN_TYPE_AUTO),
    DISABLED("disabled", FlashAttentionType.LLAMA_FLASH_ATTN_TYPE_DISABLED),
    ENABLED("enabled", FlashAttentionType.LLAMA_FLASH_ATTN_TYPE_ENABLED);

    private final String wireName;
    private final FlashAttentionType jjmlType;

    FlashAttentionMode(String wireName, FlashAttentionType jjmlType) {
        this.wireName = wireName;
        this.jjmlType = jjmlType;
    }

    FlashAttentionType getJjmlType() {
        return jjmlType;
    }

    public String wireName() {
        return wireName;
    }

    public static FlashAttentionMode parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toLowerCase();
        for (FlashAttentionMode mode : values()) {
            if (mode.wireName.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException("Unsupported flash attention mode: " + value);
    }
}
