package com.rheinmetal.tianshu.libs.nativelib;

import org.argeo.jjml.llm.LlamaCppNative;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeLibraryLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeLibraryLoader.class);

    private static final String RESOURCE_DIR = "natives/windows-x86_64";
    private static final String JJML_MANIFEST = RESOURCE_DIR + "/native-libs.txt";
    private static final String EXTERNAL_MANIFEST = "META-INF/native-libs.txt";
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
                Path nativeDir = prepareNativeDirectory();
                loadAllNativeLibraries(nativeDir);
                LlamaCppNative.ensureLibrariesLoaded();
                loadedDirectory = nativeDir;
                LOADED.set(true);
                LOGGER.info("All native libraries loaded successfully");
                printLoadReport();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load native libraries", e);
            }
        }
    }

    public static void printLoadReport() {
        LOGGER.info("=== Native Library Load Report ===");
        LOGGER.info("[jjml] LlamaCpp native: {}", verifyJjml());
        LOGGER.info("[sherpa-onnx] Native: {}", verifySherpaOnnx());
        LOGGER.info("[onnxruntime] Java API: {}", verifyOnnxruntime());
        LOGGER.info("=================================");
    }

    private static String verifyJjml() {
        try {
            LlamaCppNative.ensureLibrariesLoaded();
            return "OK";
        } catch (UnsatisfiedLinkError e) {
            return "FAILED - " + e.getMessage();
        }
    }

    private static String verifySherpaOnnx() {
        try {
            Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
            return "OK";
        } catch (UnsatisfiedLinkError e) {
            return "FAILED (DLL not loaded) - " + e.getMessage();
        } catch (ClassNotFoundException e) {
            return "NOT FOUND";
        }
    }

    private static String verifyOnnxruntime() {
        try {
            Class.forName("ai.onnxruntime.OrtEnvironment");
            return "OK";
        } catch (UnsatisfiedLinkError e) {
            return "FAILED - " + e.getMessage();
        } catch (ClassNotFoundException e) {
            return "NOT FOUND";
        }
    }

    public static Path getLoadedDirectory() {
        return loadedDirectory;
    }

    private static Path prepareNativeDirectory() throws Exception {
        List<NativeLib> allLibs = collectAllNativeLibs();
        List<NativeLib> loadableLibs = filterLoadableLibs(allLibs);

        if (loadableLibs.isEmpty()) {
            throw new IllegalStateException("No native libraries found to load");
        }

        String fingerprint = computeFingerprint(loadableLibs);
        Path baseDir = Path.of(System.getProperty("java.io.tmpdir"), TEMP_DIR_NAME);
        Files.createDirectories(baseDir);
        cleanupStaleDirectories(baseDir);

        Path targetDir = baseDir.resolve(fingerprint);
        Path fingerprintFile = targetDir.resolve(".fingerprint");

        if (Files.isDirectory(targetDir)
                && Files.isRegularFile(fingerprintFile)
                && fingerprint.equals(Files.readString(fingerprintFile).trim())
                && hasAllLibraries(targetDir, loadableLibs)) {
            LOGGER.debug("Using cached native directory: {}", targetDir);
            return targetDir;
        }

        deleteRecursively(targetDir);
        Files.createDirectories(targetDir);

        LOGGER.info("Extracting {} native libraries to: {}", loadableLibs.size(), targetDir);
        for (NativeLib lib : allLibs) {
            Path target = targetDir.resolve(lib.targetName);
            extractResource(lib.resourcePath, target);
        }

        Files.writeString(fingerprintFile, fingerprint, StandardCharsets.UTF_8);
        return targetDir;
    }

    private static List<NativeLib> filterLoadableLibs(List<NativeLib> libs) {
        List<NativeLib> loadable = new ArrayList<>();
        for (NativeLib lib : libs) {
            if (openResource(lib.resourcePath) != null) {
                loadable.add(lib);
            }
        }
        return loadable;
    }

    private static List<NativeLib> collectAllNativeLibs() throws IOException {
        List<NativeLib> result = new ArrayList<>();

        result.addAll(scanJjmlManifest());
        result.addAll(scanExternalManifest());

        return result;
    }

    private static List<NativeLib> scanExternalManifest() {
        List<NativeLib> libs = new ArrayList<>();
        try (InputStream in = openResource(EXTERNAL_MANIFEST)) {
            if (in == null) return libs;
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (name.contains("..") || !name.endsWith(".dll")) continue;
                String fileName = name.substring(name.lastIndexOf('/') + 1);
                LibSource source = detectLibSource(name);
                libs.add(new NativeLib(name, fileName, source));
            }
        } catch (IOException e) {
            LOGGER.debug("External native manifest not found");
        }
        return libs;
    }

    private static List<NativeLib> scanJjmlManifest() {
        List<NativeLib> libs = new ArrayList<>();
        try (InputStream in = openResource(JJML_MANIFEST)) {
            if (in == null) return libs;
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : content.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                libs.add(new NativeLib(RESOURCE_DIR + "/" + name, name, LibSource.JJML));
            }
        } catch (IOException e) {
            LOGGER.debug("JJML native manifest not found");
        }
        return libs;
    }

    private static LibSource detectLibSource(String resourcePath) {
        String lower = resourcePath.toLowerCase();
        if (lower.contains("onnxruntime") && lower.contains("jni")) return LibSource.ORT_JNI;
        if (lower.contains("onnxruntime") && lower.contains("providers")) return LibSource.ORT_PROVIDERS;
        if (lower.contains("sherpa") && lower.contains("jni")) return LibSource.SHERPA_JNI;
        if (lower.contains("sherpa") && lower.contains("onnxruntime")) return LibSource.SHERPA_ORT;
        return LibSource.OTHER;
    }

    private static void loadAllNativeLibraries(Path nativeDir) throws IOException {
        System.setProperty("onnxruntime.native.path", nativeDir.toAbsolutePath().toString());

        List<NativeLib> allLibs = collectAllNativeLibs();
        List<NativeLib> loadableLibs = filterLoadableLibs(allLibs);

        loadableLibs.sort(Comparator.comparingInt(NativeLibraryLoader::computeLoadPriority));

        LOGGER.info("Loading {} native libraries", loadableLibs.size());
        for (NativeLib lib : loadableLibs) {
            Path libPath = nativeDir.resolve(lib.targetName);
            if (Files.isRegularFile(libPath)) {
                LOGGER.debug("Loading: {} ({})", lib.targetName, lib.source);
                System.load(libPath.toAbsolutePath().toString());
            } else {
                LOGGER.warn("Native library not found: {}", libPath);
            }
        }

        configureJjmlPaths(nativeDir);
    }

    private static void configureJjmlPaths(Path nativeDir) {
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

    private static int computeLoadPriority(NativeLib lib) {
        String resource = lib.resourcePath.toLowerCase();
        if (resource.contains("sherpa")) return 1;
        if (resource.contains("onnxruntime")) return 15;
        String name = lib.targetName.toLowerCase();
        if (name.startsWith("ggml")) return 25;
        if (name.startsWith("llama") || name.startsWith("whisper")) return 25;
        if (name.startsWith("java_")) return 30;
        return 100;
    }

    private static String computeFingerprint(List<NativeLib> libraries) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (NativeLib lib : libraries) {
            digest.update(lib.resourcePath.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            try (InputStream in = openResource(lib.resourcePath)) {
                if (in != null) {
                    updateDigest(digest, in);
                }
            }
            digest.update((byte) 0xff);
        }
        return toHex(digest.digest());
    }

    private static void extractResource(String resource, Path target) throws IOException {
        InputStream in = openResource(resource);
        if (in == null) {
            LOGGER.warn("Native resource not found, skipping: {}", resource);
            return;
        }
        Files.createDirectories(target.getParent());
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        try (InputStream input = in;
             OutputStream out = Files.newOutputStream(tempFile)) {
            input.transferTo(out);
        }
        Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private static boolean hasAllLibraries(Path targetDir, List<NativeLib> libraries) {
        for (NativeLib lib : libraries) {
            if (!Files.isRegularFile(targetDir.resolve(lib.targetName))) return false;
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

    private static InputStream openResource(String path) {
        InputStream in = NativeLibraryLoader.class.getClassLoader().getResourceAsStream(path);
        return in != null ? new BufferedInputStream(in) : null;
    }

    private static void updateDigest(MessageDigest digest, InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private enum LibSource {
        JJML,
        ORT_JNI,
        ORT_PROVIDERS,
        SHERPA_JNI,
        SHERPA_ORT,
        OTHER
    }

    private static class NativeLib {
        final String resourcePath;
        final String targetName;
        final LibSource source;

        NativeLib(String resourcePath, String targetName, LibSource source) {
            this.resourcePath = resourcePath;
            this.targetName = targetName;
            this.source = source;
        }
    }
}