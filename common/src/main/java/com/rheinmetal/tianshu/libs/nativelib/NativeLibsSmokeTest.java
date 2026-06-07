package com.rheinmetal.tianshu.libs.nativelib;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import org.argeo.jjml.llm.LlamaCppContext;
import org.argeo.jjml.llm.LlamaCppModel;

public class NativeLibsSmokeTest {
    public static void main(String[] args) throws OrtException {
        System.out.println("==========================================");
        System.out.println("   Native Libraries Comprehensive Smoke Test");
        System.out.println("==========================================");
        System.out.println();

        int passed = 0;
        int failed = 0;

        try {
            NativeLibraryLoader.ensureLoaded();
            System.out.println("[STEP 0] Native libraries loaded: OK");
            System.out.println();
        } catch (Exception e) {
            System.err.println("[STEP 0] FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }

        // ========== 1. JJML 测试 ==========
        System.out.println("=== Test Group 1: JJML (LlamaCpp) ===");
        try {
            var modelParams = LlamaCppModel.defaultModelParams();
            System.out.println("[JJML-1] LlamaCppModel.defaultModelParams(): OK");

            var ctxParams = LlamaCppContext.defaultContextParams();
            System.out.println("[JJML-2] LlamaCppContext.defaultContextParams(): OK");

            System.out.println("[GROUP 1] JJML: PASSED");
            passed++;
            System.out.println();
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[GROUP 1] JJML: FAILED - " + e.getMessage());
            failed++;
            System.out.println();
        }

        // ========== 2. Sherpa-ONNX 测试 ==========
        System.out.println("=== Test Group 2: Sherpa-ONNX ===");
        try {
            Class<?> recognizerClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer");
            System.out.println("[Sherpa-1] Class.forName(OfflineRecognizer): OK");

            Class<?> streamClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineStream");
            System.out.println("[Sherpa-2] Class.forName(OfflineStream): OK");

            Class<?> configClass = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig");
            System.out.println("[Sherpa-3] Class.forName(OfflineRecognizerConfig): OK");

            Object config = configClass.getDeclaredMethod("builder").invoke(null);
            System.out.println("[Sherpa-4] OfflineRecognizerConfig.builder(): OK");

            System.out.println("[GROUP 2] Sherpa-ONNX: PASSED");
            passed++;
            System.out.println();
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[GROUP 2] Sherpa-ONNX: FAILED - " + e.getMessage());
            failed++;
            System.out.println();
        } catch (Exception e) {
            System.err.println("[GROUP 2] Sherpa-ONNX: ERROR - " + e.getMessage());
            e.printStackTrace();
            failed++;
            System.out.println();
        }

        // ========== 3. ONNX Runtime 测试 ==========
        System.out.println("=== Test Group 3: ONNX Runtime ===");
        try {
            OrtEnvironment env = OrtEnvironment.getEnvironment();
            System.out.println("[ONNX-1] OrtEnvironment.getEnvironment(): OK");

            float[][] inputData = {{1.0f, 2.0f, 3.0f}};
            OnnxTensor tensor = OnnxTensor.createTensor(env, inputData);
            System.out.println("[ONNX-2] OnnxTensor.createTensor(): OK");

            tensor.close();
            env.close();

            System.out.println("[GROUP 3] ONNX Runtime: PASSED");
            passed++;
            System.out.println();
        } catch (UnsatisfiedLinkError e) {
            System.err.println("[GROUP 3] ONNX Runtime: FAILED - " + e.getMessage());
            failed++;
            System.out.println();
        }

        // ========== 结果总结 ==========
        System.out.println("==========================================");
        System.out.println("   Test Results Summary");
        System.out.println("==========================================");
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);
        System.out.println();

        if (failed == 0) {
            System.out.println("*** ALL TESTS PASSED ***");
            System.out.println("All native libraries are properly loaded and functional.");
        } else {
            System.out.println("*** SOME TESTS FAILED ***");
            System.out.println("Please check the errors above.");
            System.exit(1);
        }
    }
}