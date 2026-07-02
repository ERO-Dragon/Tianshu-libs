package com.rheinmetal.tianshu.libs.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

final class MtpDraftMaxCache {
    private static final String CACHE_DIR_PROPERTY = "tianshu.libs.mtpCacheDir";
    private static final String CACHE_VERSION = "1";

    private MtpDraftMaxCache() {
    }

    static Key key(String modelPath, String draftModelPath, int gpuLayers, String device) {
        String material = "version=" + CACHE_VERSION
                + "\nmodel=" + fileSignature(modelPath)
                + "\ndraft=" + fileSignature(draftModelPath)
                + "\ngpuLayers=" + gpuLayers
                + "\ndevice=" + normalize(device);
        return new Key(sha256(material), material);
    }

    static Integer loadRecommendedDraftMax(Key key) {
        if (key == null) return null;
        Path file = cacheFile(key);
        if (!Files.isRegularFile(file)) return null;
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
            if (!CACHE_VERSION.equals(props.getProperty("version"))) return null;
            if (!key.id().equals(props.getProperty("key"))) return null;
            int draftMax = Integer.parseInt(props.getProperty("recommendedDraftMax", ""));
            if (draftMax < 1 || draftMax > MtpCalibrationRequest.MAX_CALIBRATION_DRAFT_MAX) return null;
            return draftMax;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    static void saveRecommendedDraftMax(Key key, MtpTrialResult trial) {
        if (key == null || trial == null || !trial.isSuccess()) return;
        int draftMax = trial.getDraftMax();
        if (draftMax < 1 || draftMax > MtpCalibrationRequest.MAX_CALIBRATION_DRAFT_MAX) return;
        Properties props = new Properties();
        props.setProperty("version", CACHE_VERSION);
        props.setProperty("key", key.id());
        props.setProperty("recommendedDraftMax", Integer.toString(draftMax));
        props.setProperty("tokensPerSecond", Double.toString(trial.getTokensPerSecond()));
        props.setProperty("acceptanceRate", Double.toString(trial.getAcceptanceRate()));
        props.setProperty("updatedAt", Instant.now().toString());
        props.setProperty("keyMaterial", key.material());
        Path file = cacheFile(key);
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "Tianshu libs MTP draftMax cache");
            }
        } catch (IOException e) {
            System.err.println("[LlamaEngine] Failed to write MTP draftMax cache: " + e.getMessage());
        }
    }

    private static Path cacheFile(Key key) {
        return cacheDir().resolve(key.id() + ".properties");
    }

    private static Path cacheDir() {
        String configured = System.getProperty(CACHE_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir", "."), "config", "Tianshu", "libs", "mtp")
                .toAbsolutePath()
                .normalize();
    }

    private static String fileSignature(String value) {
        if (value == null || value.isBlank()) return "<none>";
        Path path = Path.of(value).toAbsolutePath().normalize();
        try {
            return path + "|size=" + Files.size(path) + "|mtime=" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return path.toString();
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    record Key(String id, String material) {
    }
}
