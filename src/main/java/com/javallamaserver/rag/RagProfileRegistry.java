package com.javallamaserver.rag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class RagProfileRegistry {
    private static final String PROFILES_FILE = "profiles.json";
    private static final String STATIC_RAG_DIRECTORY = "static_rag";
    private static final String AGENTS_DIRECTORY = "agents";
    private static final String MEMORY_RAG_DIRECTORY = "memory_rag";
    private static final String DEFAULT_STATIC_SCOPE = "mod";
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final Path rootPath;
    private final long refreshIntervalMillis;
    private final Map<String, WorldProfiles> worlds = new HashMap<>();

    public RagProfileRegistry(String rootPath, long refreshIntervalMillis) {
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalArgumentException("RAG root path is required");
        }
        this.rootPath = Path.of(rootPath).toAbsolutePath().normalize();
        this.refreshIntervalMillis = Math.max(0, refreshIntervalMillis);
    }

    public synchronized RagProfile resolve(String world, String profile) throws IOException {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world is required for profile RAG");
        }
        WorldProfiles worldProfiles = loadWorldProfiles(world.trim());
        String profileKey = profile == null || profile.isBlank() ? worldProfiles.defaultProfile : profile.trim();
        if (profileKey == null || profileKey.isBlank()) {
            throw new IllegalArgumentException("profile is required for world: " + world);
        }
        ProfileEntry entry = worldProfiles.profiles.get(profileKey);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown RAG profile: " + world + "/" + profileKey);
        }
        if (entry.mod == null || entry.mod.isBlank() || entry.agent == null || entry.agent.isBlank()) {
            throw new IllegalArgumentException("Invalid RAG profile entry: " + world + "/" + profileKey);
        }
        String mod = entry.mod.trim();
        String agent = entry.agent.trim();
        Path worldPath = rootPath.resolve(world.trim()).normalize();
        Path staticPath = worldPath.resolve(mod).resolve(STATIC_RAG_DIRECTORY).normalize();
        Path memoryPath = worldPath.resolve(mod).resolve(AGENTS_DIRECTORY).resolve(agent).resolve(MEMORY_RAG_DIRECTORY).normalize();
        return new RagProfile(world.trim(), profileKey, mod, agent, normalizeScope(entry.static_scope), entry.memory_token_budget, staticPath, memoryPath);
    }

    public synchronized Path getWorldPath(String world) {
        if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
        return rootPath.resolve(world.trim()).normalize();
    }

    private WorldProfiles loadWorldProfiles(String world) throws IOException {
        WorldProfiles cached = worlds.get(world);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.lastCheckMillis < refreshIntervalMillis) return cached;
        Path profilesPath = rootPath.resolve(world).resolve(PROFILES_FILE).normalize();
        FileState state = readState(profilesPath);
        if (cached != null && cached.lastModifiedMillis == state.lastModifiedMillis && cached.size == state.size) {
            cached.lastCheckMillis = now;
            return cached;
        }
        if (!Files.isRegularFile(profilesPath)) {
            throw new IOException("Missing RAG profiles file: " + profilesPath);
        }
        ProfilesDocument document = gson.fromJson(Files.readString(profilesPath, StandardCharsets.UTF_8), ProfilesDocument.class);
        if (document == null || document.profiles == null || document.profiles.isEmpty()) {
            throw new IOException("Invalid RAG profiles file: " + profilesPath);
        }
        WorldProfiles loaded = new WorldProfiles();
        loaded.defaultProfile = document.default_profile;
        loaded.profiles = document.profiles;
        loaded.lastModifiedMillis = state.lastModifiedMillis;
        loaded.size = state.size;
        loaded.lastCheckMillis = now;
        worlds.put(world, loaded);
        return loaded;
    }

    private FileState readState(Path path) throws IOException {
        if (!Files.exists(path)) return new FileState(0, 0);
        return new FileState(Files.getLastModifiedTime(path).toMillis(), Files.size(path));
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) return DEFAULT_STATIC_SCOPE;
        String normalized = scope.trim().toLowerCase();
        if (normalized.equals("none") || normalized.equals("mod") || normalized.equals("world") || normalized.equals("list")) return normalized;
        return DEFAULT_STATIC_SCOPE;
    }

    private record FileState(long lastModifiedMillis, long size) {
    }

    private static class WorldProfiles {
        String defaultProfile;
        Map<String, ProfileEntry> profiles = Map.of();
        long lastModifiedMillis;
        long size;
        long lastCheckMillis;
    }

    private static class ProfilesDocument {
        String default_profile;
        Map<String, ProfileEntry> profiles;
    }

    private static class ProfileEntry {
        String mod;
        String agent;
        String static_scope;
        int memory_token_budget;
    }
}
