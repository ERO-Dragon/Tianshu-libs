package com.javallamaserver.rag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.javallamaserver.llm.EmbeddingEngine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryRagIndex {
    private static final String MEMORIES_FILE = "memories.jsonl";
    private static final String INDEX_DIRECTORY_NAME = ".javallama-memory-index";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CHUNKS_FILE = "chunks.jsonl";
    private static final String VECTORS_FILE = "vectors.bin";
    private static final int FORMAT_VERSION = 1;
    private static final int DEFAULT_SEARCH_CANDIDATES = 16;
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    private final EmbeddingEngine embeddingEngine;
    private final VectorSearch vectorSearch = new VectorSearch();
    private final Path memoryDirectory;
    private final Path memoriesFile;
    private final Path indexDirectory;
    private final long refreshIntervalMillis;
    private final List<RagChunk> chunks = new ArrayList<>();
    private long lastCheckMillis;
    private long lastModifiedMillis = -1;
    private long lastSize = -1;
    private boolean loaded;

    public MemoryRagIndex(EmbeddingEngine embeddingEngine, String path, long refreshIntervalMillis) {
        if (embeddingEngine == null) {
            throw new IllegalArgumentException("Memory RAG requires an embedding engine");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Memory RAG path is required");
        }
        this.embeddingEngine = embeddingEngine;
        this.memoryDirectory = Path.of(path).toAbsolutePath().normalize();
        this.memoriesFile = memoryDirectory.resolve(MEMORIES_FILE);
        this.indexDirectory = memoryDirectory.resolve(INDEX_DIRECTORY_NAME);
        this.refreshIntervalMillis = Math.max(0, refreshIntervalMillis);
    }

    public synchronized void load() throws Exception {
        Files.createDirectories(memoryDirectory);
        refresh(true);
    }

    public synchronized void refreshIfNeeded() throws Exception {
        refresh(false);
    }

    public synchronized List<RagSearchResult> search(float[] queryVector, int tokenBudget) throws Exception {
        refreshIfNeeded();
        if (!loaded || chunks.isEmpty() || queryVector == null || queryVector.length == 0 || tokenBudget <= 0) {
            return List.of();
        }
        List<RagSearchResult> candidates = vectorSearch.search(chunks, queryVector, Math.min(DEFAULT_SEARCH_CANDIDATES, chunks.size()));
        List<RagSearchResult> selected = new ArrayList<>();
        int used = 0;
        for (RagSearchResult candidate : candidates) {
            int cost = estimateTokens(candidate.getChunk().getText());
            if (!selected.isEmpty() && used + cost > tokenBudget) continue;
            selected.add(candidate);
            used += cost;
            if (used >= tokenBudget) break;
        }
        return selected;
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    public synchronized int size() {
        return chunks.size();
    }

    private void refresh(boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheckMillis < refreshIntervalMillis) return;
        lastCheckMillis = now;
        FileState state = readFileState();
        if (!force && loaded && state.lastModifiedMillis == lastModifiedMillis && state.size == lastSize) return;
        try {
            List<MemoryRecord> records = readRecords();
            Manifest manifest = readManifest();
            if (canLoadExactCache(manifest, state, records)) {
                chunks.clear();
                chunks.addAll(readCachedChunks(records.size()));
            } else {
                rebuildIncrementally(records, manifest);
                save(state);
            }
            lastModifiedMillis = state.lastModifiedMillis;
            lastSize = state.size;
            loaded = true;
            System.out.println("[MemoryRagIndex] Loaded memories: " + chunks.size());
        } catch (Exception e) {
            if (!loaded) throw e;
            System.err.println("[MemoryRagIndex] Refresh failed, keeping previous index: " + e.getMessage());
        }
    }

    private FileState readFileState() throws IOException {
        if (!Files.exists(memoriesFile)) return new FileState(0, 0);
        if (!Files.isRegularFile(memoriesFile)) throw new IOException("Memory RAG source is not a file: " + memoriesFile);
        return new FileState(Files.getLastModifiedTime(memoriesFile).toMillis(), Files.size(memoriesFile));
    }

    private List<MemoryRecord> readRecords() throws IOException {
        if (!Files.exists(memoriesFile)) return List.of();
        Map<String, MemoryRecord> records = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(memoriesFile, StandardCharsets.UTF_8)) {
            lineNumber++;
            if (line == null || line.isBlank()) continue;
            MemoryRecord record;
            try {
                record = gson.fromJson(line, MemoryRecord.class);
            } catch (JsonSyntaxException e) {
                throw new IOException("Invalid memory JSONL at line " + lineNumber, e);
            }
            if (record == null || record.uid == null || record.uid.isBlank() || record.long_term_memory == null || record.long_term_memory.isBlank()) {
                throw new IOException("Invalid memory record at line " + lineNumber);
            }
            String uid = record.uid.trim();
            if (records.containsKey(uid)) {
                throw new IOException("Duplicate memory uid: " + uid);
            }
            record.uid = uid;
            record.long_term_memory = record.long_term_memory.trim();
            record.textHash = hashText(record.long_term_memory);
            records.put(uid, record);
        }
        return new ArrayList<>(records.values());
    }

    private boolean canLoadExactCache(Manifest manifest, FileState state, List<MemoryRecord> records) {
        return canReuseCache(manifest)
                && manifest.lastModifiedMillis == state.lastModifiedMillis
                && manifest.size == state.size
                && manifest.count == records.size()
                && exactCacheMatches(records);
    }

    private boolean exactCacheMatches(List<MemoryRecord> records) {
        try {
            List<StoredChunk> storedChunks = readChunks();
            if (storedChunks.size() != records.size()) return false;
            for (int i = 0; i < records.size(); i++) {
                MemoryRecord record = records.get(i);
                StoredChunk storedChunk = storedChunks.get(i);
                String storedHash = storedChunk.text_hash == null || storedChunk.text_hash.isBlank()
                        ? hashText(storedChunk.long_term_memory)
                        : storedChunk.text_hash;
                if (!stringEquals(record.uid, storedChunk.uid) || !stringEquals(record.textHash, storedHash)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canReuseCache(Manifest manifest) {
        return manifest != null
                && manifest.formatVersion == FORMAT_VERSION
                && manifest.embeddingSize == embeddingEngine.getEmbeddingSize()
                && stringEquals(manifest.embeddingModel, embeddingEngine.getModelAlias());
    }

    private void rebuildIncrementally(List<MemoryRecord> records, Manifest manifest) throws Exception {
        Map<String, CachedMemoryEntry> reusable = loadReusableEntries(manifest);
        List<String> textsToEmbed = new ArrayList<>();
        List<Integer> indexesToEmbed = new ArrayList<>();
        List<CachedMemoryEntry> nextEntries = new ArrayList<>(records.size());

        for (int i = 0; i < records.size(); i++) {
            MemoryRecord record = records.get(i);
            CachedMemoryEntry old = reusable.get(record.uid);
            if (old != null && stringEquals(old.textHash, record.textHash) && old.vector != null && old.vector.length == embeddingEngine.getEmbeddingSize()) {
                nextEntries.add(new CachedMemoryEntry(record.uid, record.long_term_memory, record.textHash, old.vector));
            } else {
                nextEntries.add(null);
                textsToEmbed.add(record.long_term_memory);
                indexesToEmbed.add(i);
            }
        }

        float[][] newVectors = embeddingEngine.embed(textsToEmbed);
        for (int i = 0; i < indexesToEmbed.size(); i++) {
            int recordIndex = indexesToEmbed.get(i);
            MemoryRecord record = records.get(recordIndex);
            float[] vector = i < newVectors.length ? newVectors[i] : null;
            nextEntries.set(recordIndex, new CachedMemoryEntry(record.uid, record.long_term_memory, record.textHash, vector));
        }

        chunks.clear();
        for (CachedMemoryEntry entry : nextEntries) {
            chunks.add(new RagChunk(entry.uid, entry.longTermMemory, "memory", entry.vector));
        }
        if (!textsToEmbed.isEmpty()) {
            System.out.println("[MemoryRagIndex] Incremental embeddings: " + textsToEmbed.size() + "/" + records.size());
        }
    }

    private Map<String, CachedMemoryEntry> loadReusableEntries(Manifest manifest) {
        if (!chunks.isEmpty()) return cachedEntriesFromChunks();
        if (!canReuseCache(manifest)) return Map.of();
        try {
            List<CachedMemoryEntry> entries = readCachedEntries(manifest.count);
            Map<String, CachedMemoryEntry> byUid = new HashMap<>();
            for (CachedMemoryEntry entry : entries) {
                byUid.put(entry.uid, entry);
            }
            return byUid;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, CachedMemoryEntry> cachedEntriesFromChunks() {
        Map<String, CachedMemoryEntry> byUid = new HashMap<>();
        for (RagChunk chunk : chunks) {
            byUid.put(chunk.getId(), new CachedMemoryEntry(chunk.getId(), chunk.getText(), hashText(chunk.getText()), chunk.getVector()));
        }
        return byUid;
    }

    private void save(FileState state) throws IOException {
        Files.createDirectories(indexDirectory);
        writeChunks();
        writeVectors();
        Manifest manifest = new Manifest();
        manifest.formatVersion = FORMAT_VERSION;
        manifest.lastModifiedMillis = state.lastModifiedMillis;
        manifest.size = state.size;
        manifest.embeddingModel = embeddingEngine.getModelAlias();
        manifest.embeddingSize = embeddingEngine.getEmbeddingSize();
        manifest.count = chunks.size();
        Files.writeString(indexDirectory.resolve(MANIFEST_FILE), gson.toJson(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private Manifest readManifest() {
        Path path = indexDirectory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(path)) return null;
        try {
            return gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), Manifest.class);
        } catch (Exception e) {
            return null;
        }
    }

    private List<RagChunk> readCachedChunks(int expectedCount) throws IOException {
        List<CachedMemoryEntry> entries = readCachedEntries(expectedCount);
        List<RagChunk> loadedChunks = new ArrayList<>(entries.size());
        for (CachedMemoryEntry entry : entries) {
            loadedChunks.add(new RagChunk(entry.uid, entry.longTermMemory, "memory", entry.vector));
        }
        return loadedChunks;
    }

    private List<CachedMemoryEntry> readCachedEntries(int expectedCount) throws IOException {
        List<StoredChunk> storedChunks = readChunks();
        if (storedChunks.size() != expectedCount) throw new IOException("Memory cache count mismatch");
        if (expectedCount == 0) return List.of();
        float[][] vectors = readVectors(storedChunks.size());
        List<CachedMemoryEntry> entries = new ArrayList<>(storedChunks.size());
        for (int i = 0; i < storedChunks.size(); i++) {
            StoredChunk storedChunk = storedChunks.get(i);
            String textHash = storedChunk.text_hash == null || storedChunk.text_hash.isBlank()
                    ? hashText(storedChunk.long_term_memory)
                    : storedChunk.text_hash;
            entries.add(new CachedMemoryEntry(storedChunk.uid, storedChunk.long_term_memory, textHash, vectors[i]));
        }
        return entries;
    }

    private List<StoredChunk> readChunks() throws IOException {
        Path path = indexDirectory.resolve(CHUNKS_FILE);
        if (!Files.isRegularFile(path)) throw new IOException("Missing memory chunks cache");
        List<StoredChunk> storedChunks = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            StoredChunk storedChunk = gson.fromJson(line, StoredChunk.class);
            if (storedChunk == null || storedChunk.uid == null || storedChunk.long_term_memory == null) {
                throw new IOException("Invalid memory chunks cache");
            }
            storedChunks.add(storedChunk);
        }
        return storedChunks;
    }

    private void writeChunks() throws IOException {
        try (var writer = Files.newBufferedWriter(indexDirectory.resolve(CHUNKS_FILE), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (RagChunk chunk : chunks) {
                StoredChunk storedChunk = new StoredChunk();
                storedChunk.uid = chunk.getId();
                storedChunk.long_term_memory = chunk.getText();
                storedChunk.text_hash = hashText(chunk.getText());
                writer.write(gson.toJson(storedChunk));
                writer.newLine();
            }
        }
    }

    private float[][] readVectors(int expectedCount) throws IOException {
        Path path = indexDirectory.resolve(VECTORS_FILE);
        if (!Files.isRegularFile(path)) throw new IOException("Missing memory vectors cache");
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int count = input.readInt();
            int dimension = input.readInt();
            if (count != expectedCount) throw new IOException("Memory vector count mismatch");
            if (dimension != embeddingEngine.getEmbeddingSize()) throw new IOException("Memory vector dimension mismatch");
            float[][] vectors = new float[count][dimension];
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < dimension; j++) {
                    vectors[i][j] = input.readFloat();
                }
            }
            return vectors;
        }
    }

    private void writeVectors() throws IOException {
        if (chunks.isEmpty()) {
            Files.deleteIfExists(indexDirectory.resolve(VECTORS_FILE));
            return;
        }
        int dimension = chunks.get(0).getVector().length;
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(indexDirectory.resolve(VECTORS_FILE), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
            output.writeInt(chunks.size());
            output.writeInt(dimension);
            for (RagChunk chunk : chunks) {
                float[] vector = chunk.getVector();
                if (vector == null || vector.length != dimension) throw new IOException("Inconsistent memory vector dimension");
                for (float value : vector) {
                    output.writeFloat(value);
                }
            }
        }
    }

    private int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, (text.length() + 1) / 2);
    }

    private boolean stringEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private String hashText(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record FileState(long lastModifiedMillis, long size) {
    }

    private record CachedMemoryEntry(String uid, String longTermMemory, String textHash, float[] vector) {
    }

    private static class MemoryRecord {
        String uid;
        String long_term_memory;
        transient String textHash;
    }

    private static class StoredChunk {
        String uid;
        String long_term_memory;
        String text_hash;
    }

    private static class Manifest {
        int formatVersion;
        long lastModifiedMillis;
        long size;
        String embeddingModel;
        int embeddingSize;
        int count;
    }
}
