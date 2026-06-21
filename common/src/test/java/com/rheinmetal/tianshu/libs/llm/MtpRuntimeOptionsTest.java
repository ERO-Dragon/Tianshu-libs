package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MtpRuntimeOptionsTest {
    @Test
    void defaultsDoNotEnableMtp() {
        InferenceOptions defaults = InferenceOptions.defaults();

        assertFalse(defaults.isMtpEnabled());
        assertNull(defaults.getMtpDraftMax());
        assertNull(defaults.getVulkanPriority());
    }

    @Test
    void unsupportedMtpCapabilityDoesNotBecomeCalibrated() {
        MtpAutoTuner tuner = new MtpAutoTuner(false, 0);
        MtpCapability capability = tuner.capability();

        assertFalse(tuner.isSupported());
        assertFalse(capability.isSupported());
        assertFalse(capability.isCalibrated());
        assertEquals(0, capability.getMtpLayerCount());
        assertEquals(MtpAutoTuner.DEFAULT_DRAFT_MAX, tuner.recommendedDraftMax());
    }

    @Test
    void deviceSelectorAcceptsCommonIndexSyntax() {
        assertNull(DeviceSelector.normalize(null));
        assertNull(DeviceSelector.normalize(" "));
        assertEquals("#0", DeviceSelector.normalize("0"));
        assertEquals("#1", DeviceSelector.normalize(" 01 "));
        assertEquals("#2", DeviceSelector.normalize("#2"));
        assertEquals("Vulkan0", DeviceSelector.normalize(" Vulkan0 "));
    }

    @Test
    void failedMtpTrialDoesNotUpdateRecommendation() {
        MtpAutoTuner tuner = new MtpAutoTuner(false, 0);

        tuner.record(MtpTrialResult.failed(4, "failed"));

        assertFalse(tuner.capability().isCalibrated());
        assertEquals(MtpAutoTuner.DEFAULT_DRAFT_MAX, tuner.recommendedDraftMax());
    }
}
