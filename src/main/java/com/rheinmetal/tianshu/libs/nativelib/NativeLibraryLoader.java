package com.rheinmetal.tianshu.libs.nativelib;

import org.argeo.jjml.llm.LlamaCppNative;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeLibraryLoader {
    private static final String RESOURCE_DIR = "natives/windows-x86_64";
    private static final String MANIFEST_RESOURCE = RESOURCE_DIR + "/native-libs.txt";
    private static final String TEMP_DIR_NAME = "javallamaserver-natives";
    private static final Duration STALE_DIR_TTL = Duration.ofDays(7);
    private static final AtomicBoolean LOADED = new AtomicBoolean(false);
    private static final Object LOCK = new Object();
    private static volatile Path loadedDirectory;

    private NativeLibraryLoader() {
    }

    public static void ensureLoaded() {
        if (LOADED.get()) return;
        synchronized (LOCK) {
            if (LOADED.get()) return;
            try {
                preloadOnnxruntime();
                Path nativeDir = prepareNativeDirectory();
                configureNativePaths(nativeDir);
                LlamaCppNative.ensureLibrariesLoaded();
                loadedDirectory = nativeDir;
                LOADED.set(true);
                System.out.println("[NativeLibraryLoader] Loaded native libraries from: " + nativeDir);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load native libraries", e);
            }
        }
    }

    private static void preloadOnnxruntime() {
        try {
            Class<?> sherpaClass = Class.forName("com.k2fsa.sherpa.onnx.OnnxModel");
            System.out.println("[NativeLibraryLoader] Preloaded sherpa-onnx native libraries (extended onnxruntime.dll)");
        } catch (ClassNotFoundException e) {
            System.out.println("[NativeLibraryLoader] sherpa-onnx not found on classpath, skipping onnxruntime preload");
        } catch (UnsatisfiedLinkError e) {
            System.out.println("[NativeLibraryLoader] sherpa-onnx native preload failed (non-fatal): " + e.getMessage());
        }
    }

    public static Path getLoadedDirectory() {
        return loadedDirectory;
    }

    private static Path prepareNativeDirectory() throws Exception {
        List<String> libraries = readManifest();
        String fingerprint = computeFingerprint(libraries);
        Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        Files.createDirectories(baseDir);
        cleanupStaleDirectories(baseDir);

        Path targetDir = baseDir.resolve(fingerprint);
        Path fingerprintFile = targetDir.resolve(".fingerprint");
        if (Files.isDirectory(targetDir)
                && Files.isRegularFile(fingerprintFile)
                && fingerprint.equals(Files.readString(fingerprintFile).trim())
                && hasAllLibraries(targetDir, libraries)) {
            return targetDir;
        }

        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);
        for (String library : libraries) {
            extractResource(library, targetDir.resolve(library));
        }
        Files.writeString(fingerprintFile, fingerprint, StandardCharsets.UTF_8);
        return targetDir;
    }

    private static List<String> readManifest() throws IOException {
        try (InputStream in = openResource(MANIFEST_RESOURCE)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            List<String> libraries = new ArrayList<>();
            for (String line : content.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                validateLibraryName(name);
                libraries.add(name);
            }
            if (libraries.isEmpty()) {
                throw new IOException("Native library manifest is empty: " + MANIFEST_RESOURCE);
            }
            return libraries;
        }
    }

    private static String computeFingerprint(List<String> libraries) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String library : libraries) {
            digest.update(library.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream in = openResource(resourcePath(library))) {
                updateDigest(digest, in);
            }
            digest.update((byte) 0xff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void configureNativePaths(Path nativeDir) {
        Path ggml = requiredLibrary(nativeDir, "ggml.dll");
        Path llama = requiredLibrary(nativeDir, "llama.dll");
        Path jjmlGgml = requiredLibrary(nativeDir, "Java_org_argeo_jjml_ggml.dll");
        Path jjmlLlm = requiredLibrary(nativeDir, "Java_org_argeo_jjml_llm.dll");

        LlamaCppNative.setGgmlLibraryPath(ggml);
        LlamaCppNative.setLlamaLibraryPath(llama);
        LlamaCppNative.setJjmlLlamaLibraryPath(jjmlLlm);

        System.setProperty(LlamaCppNative.SYSTEM_PROPERTY_LIBPATH_GGML, ggml.toAbsolutePath().toString());
        System.setProperty(LlamaCppNative.SYSTEM_PROPERTY_LIBPATH_LLAMACPP, llama.toAbsolutePath().toString());
        System.setProperty(LlamaCppNative.SYSTEM_PROPERTY_LIBPATH_JJML_GGML, jjmlGgml.toAbsolutePath().toString());
        System.setProperty(LlamaCppNative.SYSTEM_PROPERTY_LIBPATH_JJML_LLM, jjmlLlm.toAbsolutePath().toString());
    }

    private static void extractResource(String library, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream in = openResource(resourcePath(library));
             OutputStream out = Files.newOutputStream(tempFile)) {
            in.transferTo(out);
        }
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean hasAllLibraries(Path targetDir, List<String> libraries) {
        for (String library : libraries) {
            if (!Files.isRegularFile(targetDir.resolve(library))) return false;
        }
        return true;
    }

    private static void cleanupStaleDirectories(Path baseDir) {
        long cutoff = System.currentTimeMillis() - STALE_DIR_TTL.toMillis();
        try (var stream = Files.list(baseDir)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                try {
                    if (Files.getLastModifiedTime(path).toMillis() < cutoff) {
                        deleteRecursively(path);
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static Path requiredLibrary(Path nativeDir, String fileName) {
        Path path = nativeDir.resolve(fileName);
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Required native library not found: " + fileName);
        }
        return path;
    }

    private static InputStream openResource(String path) throws IOException {
        InputStream in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Native resource not found in classpath: " + path);
        }
        return new BufferedInputStream(in);
    }

    private static String resourcePath(String library) {
        return RESOURCE_DIR + "/" + library;
    }

    private static void validateLibraryName(String name) throws IOException {
        if (!name.endsWith(".dll") || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("Invalid library entry in manifest: " + name);
        }
    }

    private static void updateDigest(MessageDigest digest, InputStream in) throws IOException, NoSuchAlgorithmException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
    }
}
