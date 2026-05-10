package com.javallamaserver.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class StaticRagLoader {

    public List<SourceDocument> load(String path) throws IOException {
        if (path == null || path.isBlank()) return List.of();
        Path root = Path.of(path);
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Static RAG path not found: " + path);
        }
        if (Files.isRegularFile(root)) {
            return List.of(readDocument(root));
        }
        List<SourceDocument> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(this::isSupportedTextFile)
                    .forEach(file -> {
                        try {
                            documents.add(readDocument(file));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return documents;
    }

    public List<SourceDocument> split(List<SourceDocument> documents, int chunkSize, int chunkOverlap) {
        if (documents == null || documents.isEmpty()) return List.of();
        List<SourceDocument> chunks = new ArrayList<>();
        for (SourceDocument document : documents) {
            chunks.addAll(split(document, chunkSize, chunkOverlap));
        }
        return chunks;
    }

    private List<SourceDocument> split(SourceDocument document, int chunkSize, int chunkOverlap) {
        String text = normalize(document.text());
        if (text.isBlank()) return List.of();
        List<SourceDocument> chunks = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            if (end < text.length()) {
                int paragraphBreak = text.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + chunkSize / 2) {
                    end = paragraphBreak;
                }
            }
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new SourceDocument(document.source() + "#chunk-" + index, chunkText));
                index++;
            }
            if (end >= text.length()) break;
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return chunks;
    }

    private SourceDocument readDocument(Path file) throws IOException {
        return new SourceDocument(file.toString(), Files.readString(file, StandardCharsets.UTF_8));
    }

    private boolean isSupportedTextFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json") || name.endsWith(".jsonl");
    }

    private String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    public record SourceDocument(String source, String text) {}
}
