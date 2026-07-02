package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MtpDraftMaxCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCachedRecommendedDraftMaxForMatchingKey() throws Exception {
        String oldValue = System.getProperty("tianshu.libs.mtpCacheDir");
        System.setProperty("tianshu.libs.mtpCacheDir", tempDir.toString());
        try {
            MtpDraftMaxCache.Key key = MtpDraftMaxCache.key("missing-model.gguf", "", 999, "#0");
            Properties props = new Properties();
            props.setProperty("version", "1");
            props.setProperty("key", key.id());
            props.setProperty("recommendedDraftMax", "2");
            Files.createDirectories(tempDir);
            try (OutputStream out = Files.newOutputStream(tempDir.resolve(key.id() + ".properties"))) {
                props.store(out, "test");
            }

            assertEquals(2, MtpDraftMaxCache.loadRecommendedDraftMax(key));
        } finally {
            restoreProperty(oldValue);
        }
    }

    @Test
    void ignoreCachedRecommendedDraftMaxForWrongKeyOrInvalidRange() throws Exception {
        String oldValue = System.getProperty("tianshu.libs.mtpCacheDir");
        System.setProperty("tianshu.libs.mtpCacheDir", tempDir.toString());
        try {
            MtpDraftMaxCache.Key key = MtpDraftMaxCache.key("missing-model.gguf", "", 999, "#0");
            Properties props = new Properties();
            props.setProperty("version", "1");
            props.setProperty("key", "wrong");
            props.setProperty("recommendedDraftMax", "2");
            Files.createDirectories(tempDir);
            try (OutputStream out = Files.newOutputStream(tempDir.resolve(key.id() + ".properties"))) {
                props.store(out, "test");
            }

            assertNull(MtpDraftMaxCache.loadRecommendedDraftMax(key));

            props.setProperty("key", key.id());
            props.setProperty("recommendedDraftMax", "0");
            try (OutputStream out = Files.newOutputStream(tempDir.resolve(key.id() + ".properties"))) {
                props.store(out, "test");
            }

            assertNull(MtpDraftMaxCache.loadRecommendedDraftMax(key));
        } finally {
            restoreProperty(oldValue);
        }
    }

    private static void restoreProperty(String oldValue) {
        if (oldValue == null) {
            System.clearProperty("tianshu.libs.mtpCacheDir");
        } else {
            System.setProperty("tianshu.libs.mtpCacheDir", oldValue);
        }
    }
}
