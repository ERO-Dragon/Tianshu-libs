package com.rheinmetal.tianshu.libs.nativelib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
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
    private static final List<String> JJML_JNI_ENTRYPOINT_LIBRARIES = List.of(
            "Java_org_argeo_jjml_ggml.dll",
            "Java_org_argeo_jjml_llm.dll",
            "Java_org_argeo_jjml_mtmd.dll",
            "Java_org_argeo_jjml_whisper.dll"
    );
    private static final List<String> JJML_CORE_DEPENDENCY_LOAD_ORDER = List.of(
            "ggml-base.dll",
            "ggml.dll",
            "llama.dll",
            "llama-common.dll",
            "whisper.dll",
            "parakeet.dll"
    );

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
                NativeLoadPlan loadPlan = createNativeLoadPlan();
                Path nativeDir = prepareNativeDirectory(loadPlan.allLibraries);
                configureJjmlPaths(nativeDir);
                preloadNativeDependencies(nativeDir, loadPlan.preloadLibraries);
                loadedDirectory = nativeDir;
                LOADED.set(true);
                LOGGER.info("All native libraries loaded successfully");
                printLoadReport();
            } catch (Exception | LinkageError e) {
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
            String ggmlPath = System.getProperty("org.argeo.jjml.llm.ggml.libpath");
            String llamaPath = System.getProperty("org.argeo.jjml.llm.llama.libpath");
            String jjmlGgmlPath = System.getProperty("jjml.libpath.jjml.ggml");
            String jjmlLlmPath = System.getProperty("jjml.libpath.jjml.llm");
            if (isRegularFile(ggmlPath) && isRegularFile(llamaPath)
                    && isRegularFile(jjmlGgmlPath) && isRegularFile(jjmlLlmPath)) {
                return "OK";
            }
            return "FAILED - paths not configured";
        } catch (Exception e) {
            return "FAILED - " + e.getMessage();
        }
    }

    private static boolean isRegularFile(String path) {
        return path != null && Files.isRegularFile(Path.of(path));
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

    private static NativeLoadPlan createNativeLoadPlan() throws IOException {
        List<NativeLib> allLibs = collectAllNativeLibs();
        List<NativeLib> loadableLibs = filterLoadableLibs(allLibs);
        validateUniqueTargetNames(loadableLibs);

        List<NativeLib> preloadLibs = new ArrayList<>();
        List<NativeLib> jjmlManagedLibs = new ArrayList<>();
        for (NativeLib lib : loadableLibs) {
            if (isManagedByJjml(lib)) {
                jjmlManagedLibs.add(lib);
            } else {
                preloadLibs.add(lib);
            }
        }

        LOGGER.debug("Native load plan: {} total, {} preloaded, {} managed by JJML",
                loadableLibs.size(), preloadLibs.size(), jjmlManagedLibs.size());
        return new NativeLoadPlan(loadableLibs, preloadLibs, jjmlManagedLibs);
    }

    private static void validateUniqueTargetNames(List<NativeLib> libs) {
        for (int i = 0; i < libs.size(); i++) {
            NativeLib current = libs.get(i);
            for (int j = i + 1; j < libs.size(); j++) {
                NativeLib other = libs.get(j);
                if (current.targetName.equalsIgnoreCase(other.targetName)) {
                    throw new IllegalStateException("Native library name collision: "
                            + current.targetName + " from " + current.resourcePath
                            + " and " + other.resourcePath);
                }
            }
        }
    }

    private static Path prepareNativeDirectory(List<NativeLib> loadableLibs) throws Exception {

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
        for (NativeLib lib : loadableLibs) {
            Path target = targetDir.resolve(lib.targetName);
            extractResource(lib.resourcePath, target);
        }

        Files.writeString(fingerprintFile, fingerprint, StandardCharsets.UTF_8);
        return targetDir;
    }

    private static List<NativeLib> filterLoadableLibs(List<NativeLib> libs) {
        List<NativeLib> loadable = new ArrayList<>();
        for (NativeLib lib : libs) {
            try (InputStream in = openResource(lib.resourcePath)) {
                if (in == null) continue;
                loadable.add(lib);
            } catch (IOException ignored) {
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

    private static void preloadNativeDependencies(Path nativeDir, List<NativeLib> preloadLibs) {
        System.setProperty("onnxruntime.native.path", nativeDir.toAbsolutePath().toString());

        preloadLibs.sort(Comparator.comparingInt(NativeLibraryLoader::computeLoadPriority));

        LOGGER.info("Preloading {} native dependency libraries", preloadLibs.size());
        for (NativeLib lib : preloadLibs) {
            Path libPath = nativeDir.resolve(lib.targetName);
            if (Files.isRegularFile(libPath)) {
                LOGGER.debug("Preloading: {} ({})", lib.targetName, lib.source);
                try {
                    System.load(libPath.toAbsolutePath().toString());
                } catch (UnsatisfiedLinkError e) {
                    throw new UnsatisfiedLinkError("Failed to preload native dependency "
                            + lib.targetName + " from " + libPath.toAbsolutePath()
                            + " (" + lib.source + "): " + e.getMessage());
                }
            } else {
                LOGGER.warn("Native library not found: {}", libPath);
            }
        }
    }

    private static void configureJjmlPaths(Path nativeDir) {
        Path ggml = requiredLibrary(nativeDir, "ggml.dll");
        Path llama = requiredLibrary(nativeDir, "llama.dll");
        Path jjmlGgml = requiredLibrary(nativeDir, "Java_org_argeo_jjml_ggml.dll");
        Path jjmlLlm = requiredLibrary(nativeDir, "Java_org_argeo_jjml_llm.dll");

        System.setProperty("jjml.libpath.jjml.ggml", jjmlGgml.toAbsolutePath().toString());
        System.setProperty("jjml.libpath.jjml.llm", jjmlLlm.toAbsolutePath().toString());
        prependJavaLibraryPath(nativeDir);

        // Keep the older internal names for compatibility with callers that inspect them.
        System.setProperty("org.argeo.jjml.llm.ggml.libpath", ggml.toAbsolutePath().toString());
        System.setProperty("org.argeo.jjml.llm.llama.libpath", llama.toAbsolutePath().toString());
        System.setProperty("org.argeo.jjml.llm.jjml.ggml.libpath", jjmlGgml.toAbsolutePath().toString());
        System.setProperty("org.argeo.jjml.llm.jjml.llm.libpath", jjmlLlm.toAbsolutePath().toString());
    }

    private static boolean isManagedByJjml(NativeLib lib) {
        if (lib.source != LibSource.JJML) return false;
        if (isJjmlBackendPlugin(lib.targetName)) return true;
        for (String libraryName : JJML_JNI_ENTRYPOINT_LIBRARIES) {
            if (libraryName.equalsIgnoreCase(lib.targetName)) return true;
        }
        return false;
    }

    private static boolean isJjmlBackendPlugin(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.startsWith("ggml-cpu-")
                || lower.startsWith("ggml-cuda")
                || lower.startsWith("ggml-hip")
                || lower.startsWith("ggml-blas")
                || lower.startsWith("ggml-rpc")
                || lower.startsWith("ggml-cann")
                || lower.startsWith("ggml-metal")
                || lower.startsWith("ggml-sycl")
                || lower.startsWith("ggml-opencl")
                || lower.startsWith("ggml-musa")
                || lower.equals("ggml-vulkan.dll");
    }

    private static void prependJavaLibraryPath(Path nativeDir) {
        String nativeDirPath = nativeDir.toAbsolutePath().toString();
        String currentPath = System.getProperty("java.library.path", "");
        for (String entry : currentPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (nativeDirPath.equals(entry)) return;
        }
        String updatedPath = currentPath.isBlank()
                ? nativeDirPath
                : nativeDirPath + File.pathSeparator + currentPath;
        System.setProperty("java.library.path", updatedPath);
    }

    private static int computeLoadPriority(NativeLib lib) {
        if (lib.source == LibSource.JJML) {
            int priority = jjmlCoreDependencyPriority(lib.targetName);
            return priority >= 0 ? priority : 50;
        }
        if (lib.source == LibSource.SHERPA_ORT) return 10;
        if (lib.source == LibSource.ORT_PROVIDERS) return 20;
        if (lib.source == LibSource.ORT_JNI) return 30;
        if (lib.source == LibSource.SHERPA_JNI) return 40;
        String name = lib.targetName.toLowerCase();
        if (name.contains("onnxruntime")) return 15;
        if (name.endsWith("-jni.dll")) return 50;
        return 100;
    }

    private static int jjmlCoreDependencyPriority(String fileName) {
        for (int i = 0; i < JJML_CORE_DEPENDENCY_LOAD_ORDER.size(); i++) {
            if (JJML_CORE_DEPENDENCY_LOAD_ORDER.get(i).equalsIgnoreCase(fileName)) {
                return i + 1;
            }
        }
        return -1;
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

    private static class NativeLoadPlan {
        final List<NativeLib> allLibraries;
        final List<NativeLib> preloadLibraries;
        final List<NativeLib> jjmlManagedLibraries;

        NativeLoadPlan(List<NativeLib> allLibraries, List<NativeLib> preloadLibraries,
                       List<NativeLib> jjmlManagedLibraries) {
            this.allLibraries = allLibraries;
            this.preloadLibraries = preloadLibraries;
            this.jjmlManagedLibraries = jjmlManagedLibraries;
        }
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
