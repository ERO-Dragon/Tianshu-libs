package com.rheinmetal.tianshu.libs.nativelib;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import org.argeo.jjml.llm.LlamaCppChatMessage;
import org.argeo.jjml.llm.LlamaCppNativeSampler;
import org.argeo.jjml.llm.LlamaCppSamplers;
import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class NativeApiSmokeTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(NativeApiSmokeTest.class);
    private static final AtomicBoolean RAN = new AtomicBoolean(false);

    private NativeApiSmokeTest() {
    }

    public static void runOnce() {
        if (Boolean.getBoolean("tianshu.libs.skipApiSmokeTest")) {
            LOGGER.info("Native API smoke test skipped by system property");
            return;
        }
        if (!RAN.compareAndSet(false, true)) return;

        LOGGER.info("=== Native API Smoke Test ===");
        try {
            verifyJjmlApi();
            verifySherpaApi();
            verifyOnnxRuntimeApi();
            LOGGER.info("Native API smoke test: OK");
        } catch (Throwable t) {
            LOGGER.error("Native API smoke test: FAILED", t);
            throw new IllegalStateException("Native API smoke test failed", t);
        } finally {
            LOGGER.info("=============================");
        }
    }

    private static void verifyJjmlApi() {
        var modelParams = LlamaCppModel.defaultModelParams();
        var contextParams = LlamaCppContext.defaultContextParams();
        var chatMessage = new LlamaCppChatMessage("user", "ping");

        try (LlamaCppNativeSampler sampler = LlamaCppSamplers.newSamplerGreedy()) {
            long samplerHandle = sampler.getAsLong();
            if (samplerHandle == 0L) {
                throw new IllegalStateException("JJML greedy sampler returned a null native handle");
            }
        }

        LOGGER.info("[api][jjml] OK - modelParams={}, contextSize={}, chatRole={}",
                modelParams.getClass().getSimpleName(), contextParams.n_ctx(), chatMessage.getRole());
    }

    private static void verifySherpaApi() {
        OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                .setDecodingMethod("greedy_search")
                .setMaxActivePaths(4)
                .build();
        if (config == null) {
            throw new IllegalStateException("Sherpa OfflineRecognizerConfig.builder().build() returned null");
        }

        LOGGER.info("[api][sherpa-onnx] OK - OfflineRecognizerConfig builder is callable");
    }

    private static void verifyOnnxRuntimeApi() throws Exception {
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        float[][] inputData = {{1.0f, 2.0f, 3.0f}};
        try (OnnxTensor tensor = OnnxTensor.createTensor(environment, inputData)) {
            if (tensor.getType() == null) {
                throw new IllegalStateException("ONNX tensor type is null");
            }
            LOGGER.info("[api][onnxruntime] OK - version={}, tensorType={}",
                    environment.getVersion(), tensor.getType());
        }
    }
}
