package com.rheinmetal.tianshu.libs.rag;

import java.nio.file.Path;

public class RagProfile {
    private final String world;
    private final String key;
    private final String mod;
    private final String agent;
    private final String staticScope;
    private final int memoryTokenBudget;
    private final Path staticPath;
    private final Path memoryPath;

    public RagProfile(String world, String key, String mod, String agent, String staticScope, int memoryTokenBudget, Path staticPath, Path memoryPath) {
        this.world = world;
        this.key = key;
        this.mod = mod;
        this.agent = agent;
        this.staticScope = staticScope;
        this.memoryTokenBudget = memoryTokenBudget;
        this.staticPath = staticPath;
        this.memoryPath = memoryPath;
    }

    public String getWorld() {
        return world;
    }

    public String getKey() {
        return key;
    }

    public String getMod() {
        return mod;
    }

    public String getAgent() {
        return agent;
    }

    public String getStaticScope() {
        return staticScope;
    }

    public int getMemoryTokenBudget() {
        return memoryTokenBudget;
    }

    public Path getStaticPath() {
        return staticPath;
    }

    public Path getMemoryPath() {
        return memoryPath;
    }
}
