package com.rheinmetal.tianshu.libs.rag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorldStaticRagRegistry {
    private static final String STATIC_RAG_DIRECTORY = "static_rag";

    private final long refreshIntervalMillis;
    private final Map<Path, CachedWorldStatics> cache = new HashMap<>();

    public WorldStaticRagRegistry(long refreshIntervalMillis) {
        this.refreshIntervalMillis = Math.max(0, refreshIntervalMillis);
    }

    public synchronized List<Path> discover(Path worldPath) throws IOException {
        Path normalizedWorldPath = worldPath.toAbsolutePath().normalize();
        long now = System.currentTimeMillis();
        CachedWorldStatics cached = cache.get(normalizedWorldPath);
        if (cached != null && now - cached.lastCheckMillis < refreshIntervalMillis) return cached.staticPaths;
        List<Path> staticPaths = new ArrayList<>();
        if (Files.isDirectory(normalizedWorldPath)) {
            try (var children = Files.list(normalizedWorldPath)) {
                List<Path> mods = children.filter(Files::isDirectory).sorted().toList();
                for (Path modPath : mods) {
                    Path staticPath = modPath.resolve(STATIC_RAG_DIRECTORY).toAbsolutePath().normalize();
                    if (Files.isDirectory(staticPath)) staticPaths.add(staticPath);
                }
            }
        }
        CachedWorldStatics next = new CachedWorldStatics();
        next.staticPaths = List.copyOf(staticPaths);
        next.lastCheckMillis = now;
        cache.put(normalizedWorldPath, next);
        return next.staticPaths;
    }

    private static class CachedWorldStatics {
        List<Path> staticPaths = List.of();
        long lastCheckMillis;
    }
}
