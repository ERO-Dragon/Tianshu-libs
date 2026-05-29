package com.rheinmetal.tianshu.libs.llm;

public enum InferenceLane {
    CHAT("chat"),
    TASK("task");

    private final String wireName;

    InferenceLane(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static InferenceLane parse(String value) {
        if (value == null || value.isBlank()) return CHAT;
        String normalized = value.trim().toLowerCase();
        for (InferenceLane lane : values()) {
            if (lane.wireName.equals(normalized)) return lane;
        }
        throw new IllegalArgumentException("Unsupported lane: " + value);
    }
}
