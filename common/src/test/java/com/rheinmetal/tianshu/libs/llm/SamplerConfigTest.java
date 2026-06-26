package com.rheinmetal.tianshu.libs.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SamplerConfigTest {
    @Test
    void defaultsAreValid() {
        assertDoesNotThrow(() -> SamplerConfig.defaults().validate());
    }

    @Test
    void rejectsInvalidSamplerValuesBeforeNativeSamplerCreation() {
        SamplerConfig sampler = SamplerConfig.defaults();
        sampler.setTemperature(Float.NaN);
        assertThrows(IllegalArgumentException.class, sampler::validate);

        sampler = SamplerConfig.defaults();
        sampler.setTopK(-1);
        assertThrows(IllegalArgumentException.class, sampler::validate);

        sampler = SamplerConfig.defaults();
        sampler.setTopP(1.1f);
        assertThrows(IllegalArgumentException.class, sampler::validate);

        sampler = SamplerConfig.defaults();
        sampler.setPenaltyLastN(-2);
        assertThrows(IllegalArgumentException.class, sampler::validate);
    }

    @Test
    void requiresGrammarRootWhenGrammarIsConfigured() {
        SamplerConfig sampler = SamplerConfig.defaults();
        sampler.setGrammarStr("root ::= \"ok\"");
        sampler.setGrammarRoot(" ");

        assertThrows(IllegalArgumentException.class, sampler::validate);
    }
}
