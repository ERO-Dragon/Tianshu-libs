package com.javallamaserver.rag;

import java.util.List;

public class RagQueryOptions {
    private final String world;
    private final String profile;
    private final String staticScope;
    private final List<String> staticMods;

    public RagQueryOptions(String world, String profile, String staticScope, List<String> staticMods) {
        this.world = normalize(world);
        this.profile = normalize(profile);
        this.staticScope = normalize(staticScope);
        this.staticMods = staticMods == null ? List.of() : staticMods.stream().filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    public String getWorld() {
        return world;
    }

    public String getProfile() {
        return profile;
    }

    public String getStaticScope() {
        return staticScope;
    }

    public List<String> getStaticMods() {
        return staticMods;
    }

    public boolean hasProfile() {
        return world != null && profile != null;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }
}
