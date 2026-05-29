package com.rheinmetal.tianshu.libs.rag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.rheinmetal.tianshu.libs.llm.EmbeddingEngine;

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
import java.util.HexFormat;
import java.util.List;

public class StaticRagIndexStore {
    public static final String INDEX_DIRECTORY_NAME = ".javallama-rag-index";
    private static final int FORMAT_VERSION = 1;
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String CHUNKS_FILE = "chunks.jsonl";
    private static final String VECTORS_FILE = "vectors.bin";
    private static final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

    public boolean canLoad(Path indexDirectory, IndexDescriptor descriptor) {
        try {
            StoredManifest manifest = readManifest(indexDirectory);
            return manifest != null && manifest.matches(descriptor);
        } catch (Exception e) {
            System.err.println("[StaticRagIndexStore] Cache manifest is invalid: " + e.getMessage());
            return false;
        }
    }

    public List<RagChunk> load(Path indexDirectory) throws IOException {
        List<StoredChunk> storedChunks = readChunks(indexDirectory.resolve(CHUNKS_FILE));
        float[][] vectors = readVectors(indexDirectory.resolve(VECTORS_FILE), storedChunks.size());
        List<RagChunk> chunks = new ArrayList<>(storedChunks.size());
        for (int i = 0; i < storedChunks.size(); i++) {
            StoredChunk storedChunk = storedChunks.get(i);
            chunks.add(new RagChunk(storedChunk.id, storedChunk.text, storedChunk.source, vectors[i]));
        }
        return chunks;
    }

    public void save(Path indexDirectory, IndexDescriptor descriptor, List<RagChunk> chunks) throws IOException {
        Files.createDirectories(indexDirectory);
        writeChunks(indexDirectory.resolve(CHUNKS_FILE), chunks);
        writeVectors(indexDirectory.resolve(VECTORS_FILE), chunks);
        writeManifest(indexDirectory.resolve(MANIFEST_FILE), StoredManifest.from(descriptor, chunks));
    }

    public IndexDescriptor describe(String path, RagConfig config, EmbeddingEngine embeddingEngine) throws IOException {
        Path sourcePath = Path.of(path).toAbsolutePath().normalize();
        Path indexDirectory = resolveIndexDirectory(sourcePath);
        return new IndexDescriptor(
                FORMAT_VERSION,
                sourcePath.toString(),
                fingerprint(sourcePath, indexDirectory),
                config.getChunkSize(),
                config.getChunkOverlap(),
                embeddingEngine.getModelAlias(),
                embeddingEngine.getEmbeddingSize()
        );
    }

    public Path defaultIndexDirectory(String path, RagConfig config, EmbeddingEngine embeddingEngine) throws IOException {
        Path sourcePath = Path.of(path).toAbsolutePath().normalize();
        Path indexRoot = resolveIndexDirectory(sourcePath);
        IndexDescriptor descriptor = describe(path, config, embeddingEngine);
        String key = sha256(descriptor.sourcePath + "\n" + descriptor.sourceFingerprint + "\n" + descriptor.chunkSize + "\n" + descriptor.chunkOverlap + "\n" + descriptor.embeddingModel + "\n" + descriptor.embeddingSize);
        return indexRoot.resolve(key);
    }

    public Path resolveIndexDirectory(Path sourcePath) {
        if (Files.isRegularFile(sourcePath)) {
            Path parent = sourcePath.getParent();
            if (parent == null) parent = Path.of(".").toAbsolutePath().normalize();
            return parent.resolve(INDEX_DIRECTORY_NAME).resolve(sourcePath.getFileName().toString());
        }
        return sourcePath.resolve(INDEX_DIRECTORY_NAME);
    }

    private StoredManifest readManifest(Path indexDirectory) throws IOException {
        Path manifestPath = indexDirectory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) return null;
        try {
            return gson.fromJson(Files.readString(manifestPath, StandardCharsets.UTF_8), StoredManifest.class);
        } catch (JsonSyntaxException e) {
            throw new IOException("Invalid manifest JSON", e);
        }
    }

    private void writeManifest(Path manifestPath, StoredManifest manifest) throws IOException {
        Files.writeString(manifestPath, gson.toJson(manifest), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private List<StoredChunk> readChunks(Path chunksPath) throws IOException {
        List<StoredChunk> chunks = new ArrayList<>();
        if (!Files.isRegularFile(chunksPath)) throw new IOException("Missing chunks file: " + chunksPath);
        for (String line : Files.readAllLines(chunksPath, StandardCharsets.UTF_8)) {
            if (line == null || line.isBlank()) continue;
            StoredChunk chunk = gson.fromJson(line, StoredChunk.class);
            if (chunk == null || chunk.text == null || chunk.text.isBlank()) {
                throw new IOException("Invalid chunk entry in " + chunksPath);
            }
            chunks.add(chunk);
        }
        return chunks;
    }

    private void writeChunks(Path chunksPath, List<RagChunk> chunks) throws IOException {
        try (var writer = Files.newBufferedWriter(chunksPath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            for (RagChunk chunk : chunks) {
                writer.write(gson.toJson(StoredChunk.from(chunk)));
                writer.newLine();
            }
        }
    }

    private float[][] readVectors(Path vectorsPath, int expectedCount) throws IOException {
        if (!Files.isRegularFile(vectorsPath)) throw new IOException("Missing vectors file: " + vectorsPath);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(vectorsPath)))) {
            int count = input.readInt();
            int dimension = input.readInt();
            if (count != expectedCount) {
                throw new IOException("Vector count does not match chunks: " + count + " != " + expectedCount);
            }
            if (dimension <= 0) {
                throw new IOException("Invalid vector dimension: " + dimension);
            }
            float[][] vectors = new float[count][dimension];
            for (int i = 0; i < count; i++) {
                for (int j = 0; j < dimension; j++) {
                    vectors[i][j] = input.readFloat();
                }
            }
            return vectors;
        }
    }

    private void writeVectors(Path vectorsPath, List<RagChunk> chunks) throws IOException {
        int dimension = resolveDimension(chunks);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(vectorsPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)))) {
            output.writeInt(chunks.size());
            output.writeInt(dimension);
            for (RagChunk chunk : chunks) {
                float[] vector = chunk.getVector();
                if (vector == null || vector.length != dimension) {
                    throw new IOException("Inconsistent vector dimension for chunk: " + chunk.getId());
                }
                for (float value : vector) {
                    output.writeFloat(value);
                }
            }
        }
    }

    private int resolveDimension(List<RagChunk> chunks) throws IOException {
        for (RagChunk chunk : chunks) {
            float[] vector = chunk.getVector();
            if (vector != null && vector.length > 0) return vector.length;
        }
        throw new IOException("No vectors to persist");
    }

    private String fingerprint(Path sourcePath, Path excludedRoot) throws IOException {
        MessageDigest digest = newDigest();
        if (Files.isRegularFile(sourcePath)) {
            updateDigest(digest, sourcePath);
        } else {
            try (var paths = Files.walk(sourcePath)) {
                List<Path> files = paths.filter(Files::isRegularFile)
                        .filter(file -> !isUnderExcludedRoot(file, excludedRoot))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    updateDigest(digest, file);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean isUnderExcludedRoot(Path file, Path excludedRoot) {
        if (excludedRoot == null) return false;
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path normalizedRoot = excludedRoot.toAbsolutePath().normalize();
        return normalizedFile.startsWith(normalizedRoot);
    }

    private void updateDigest(MessageDigest digest, Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        digest.update(absolute.toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.size(file)).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(Long.toString(Files.getLastModifiedTime(file).toMillis()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private String sha256(String value) {
        MessageDigest digest = newDigest();
        return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public record IndexDescriptor(int formatVersion, String sourcePath, String sourceFingerprint, int chunkSize, int chunkOverlap, String embeddingModel, int embeddingSize) {
    }

    private static class StoredManifest {
        int formatVersion;
        String sourcePath;
        String sourceFingerprint;
        int chunkSize;
        int chunkOverlap;
        String embeddingModel;
        int embeddingSize;
        int chunkCount;

        static StoredManifest from(IndexDescriptor descriptor, List<RagChunk> chunks) {
            StoredManifest manifest = new StoredManifest();
            manifest.formatVersion = descriptor.formatVersion();
            manifest.sourcePath = descriptor.sourcePath();
            manifest.sourceFingerprint = descriptor.sourceFingerprint();
            manifest.chunkSize = descriptor.chunkSize();
            manifest.chunkOverlap = descriptor.chunkOverlap();
            manifest.embeddingModel = descriptor.embeddingModel();
            manifest.embeddingSize = descriptor.embeddingSize();
            manifest.chunkCount = chunks.size();
            return manifest;
        }

        boolean matches(IndexDescriptor descriptor) {
            return formatVersion == descriptor.formatVersion()
                    && chunkSize == descriptor.chunkSize()
                    && chunkOverlap == descriptor.chunkOverlap()
                    && embeddingSize == descriptor.embeddingSize()
                    && stringEquals(sourcePath, descriptor.sourcePath())
                    && stringEquals(sourceFingerprint, descriptor.sourceFingerprint())
                    && stringEquals(embeddingModel, descriptor.embeddingModel());
        }

        private boolean stringEquals(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }
    }

    private static class StoredChunk {
        String id;
        String text;
        String source;

        static StoredChunk from(RagChunk chunk) {
            StoredChunk storedChunk = new StoredChunk();
            storedChunk.id = chunk.getId();
            storedChunk.text = chunk.getText();
            storedChunk.source = chunk.getSource();
            return storedChunk;
        }
    }
}
