package com.rheinmetal.tianshu.libs.llm;

import org.argeo.jjml.llm.util.ThinkingMode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class SamplerConfig {
    private static final float MAX_TEMPERATURE = 10.0f;
    private static final float MAX_PENALTY_REPEAT = 10.0f;
    private static final float MIN_ADDITIVE_PENALTY = -10.0f;
    private static final float MAX_ADDITIVE_PENALTY = 10.0f;

    public Float temperature;
    public Integer topK;
    public Float topP;
    public Float minP;
    public Float penaltyRepeat;
    public Float penaltyFreq;
    public Float penaltyPresent;
    public Integer penaltyLastN;
    public Boolean enableThinking;
    public ThinkingMode thinkingMode;
    public Map<String, String> chatTemplateKwargs;
    public String grammarStr;
    public String grammarRoot;

    public SamplerConfig() {
        this.temperature = 0.0f;
        this.topK = 40;
        this.topP = 0.95f;
        this.minP = 0.05f;
        this.penaltyRepeat = 1.0f;
        this.penaltyFreq = 0.0f;
        this.penaltyPresent = 0.0f;
        this.penaltyLastN = 64;
        this.enableThinking = null;
        this.thinkingMode = ThinkingMode.AUTO;
        this.grammarRoot = "root";
    }

    public SamplerConfig(SamplerConfig source) {
        this();
        if (source == null) return;
        this.temperature = source.temperature;
        this.topK = source.topK;
        this.topP = source.topP;
        this.minP = source.minP;
        this.penaltyRepeat = source.penaltyRepeat;
        this.penaltyFreq = source.penaltyFreq;
        this.penaltyPresent = source.penaltyPresent;
        this.penaltyLastN = source.penaltyLastN;
        this.enableThinking = source.enableThinking;
        this.thinkingMode = source.thinkingMode;
        this.chatTemplateKwargs = source.chatTemplateKwargs == null
                ? null
                : new LinkedHashMap<>(source.chatTemplateKwargs);
        this.grammarStr = source.grammarStr;
        this.grammarRoot = source.grammarRoot;
    }

    public static SamplerConfig defaults() {
        return new SamplerConfig();
    }

    public SamplerConfig copy() {
        return new SamplerConfig(this);
    }

    public void validate() {
        requireFiniteRange(temperature(), 0.0f, MAX_TEMPERATURE, "temperature");
        requireNonNegative(topK(), "topK");
        requireFiniteRange(topP(), 0.0f, 1.0f, "topP");
        requireFiniteRange(minP(), 0.0f, 1.0f, "minP");
        requireFiniteRange(penaltyRepeat(), 0.0f, MAX_PENALTY_REPEAT, "penaltyRepeat");
        requireFiniteRange(penaltyFreq(), MIN_ADDITIVE_PENALTY, MAX_ADDITIVE_PENALTY, "penaltyFreq");
        requireFiniteRange(penaltyPresent(), MIN_ADDITIVE_PENALTY, MAX_ADDITIVE_PENALTY, "penaltyPresent");
        if (penaltyLastN() < -1) {
            throw new IllegalArgumentException("penaltyLastN must be -1 or greater");
        }
        if (hasGrammar() && (grammarRoot == null || grammarRoot.isBlank())) {
            throw new IllegalArgumentException("grammarRoot cannot be blank when grammarStr is set");
        }
    }

    public Float getTemperature() { return temperature; }
    public void setTemperature(Float temperature) { this.temperature = temperature; }

    public Integer getTopK() { return topK; }
    public void setTopK(Integer topK) { this.topK = topK; }

    public Float getTopP() { return topP; }
    public void setTopP(Float topP) { this.topP = topP; }

    public Float getMinP() { return minP; }
    public void setMinP(Float minP) { this.minP = minP; }

    public Float getPenaltyRepeat() { return penaltyRepeat; }
    public void setPenaltyRepeat(Float penaltyRepeat) { this.penaltyRepeat = penaltyRepeat; }

    public Float getPenaltyFreq() { return penaltyFreq; }
    public void setPenaltyFreq(Float penaltyFreq) { this.penaltyFreq = penaltyFreq; }

    public Float getPenaltyPresent() { return penaltyPresent; }
    public void setPenaltyPresent(Float penaltyPresent) { this.penaltyPresent = penaltyPresent; }

    public Integer getPenaltyLastN() { return penaltyLastN; }
    public void setPenaltyLastN(Integer penaltyLastN) { this.penaltyLastN = penaltyLastN; }

    public Boolean getEnableThinking() {
        return enableThinking;
    }

    public void setEnableThinking(Boolean enableThinking) {
        this.enableThinking = enableThinking;
    }

    public ThinkingMode getThinkingMode() { return thinkingMode; }
    public void setThinkingMode(ThinkingMode thinkingMode) {
        this.enableThinking = null;
        this.thinkingMode = thinkingMode;
    }

    public ThinkingMode getEffectiveThinkingMode() { return effectiveThinkingMode(); }

    public Map<String, String> getChatTemplateKwargs() { return chatTemplateKwargs; }
    public void setChatTemplateKwargs(Map<String, String> kwargs) { this.chatTemplateKwargs = kwargs; }

    public SamplerConfig withKwargs(String key, String value) {
        return chatTemplateKwarg(key, value);
    }

    public SamplerConfig chatTemplateKwarg(String key, String value) {
        if (chatTemplateKwargs == null) {
            chatTemplateKwargs = new LinkedHashMap<>();
        }
        chatTemplateKwargs.put(key, value);
        return this;
    }

    public String getGrammarStr() { return grammarStr; }
    public void setGrammarStr(String grammarStr) { this.grammarStr = grammarStr; }

    public String getGrammarRoot() { return grammarRoot; }
    public void setGrammarRoot(String grammarRoot) { this.grammarRoot = grammarRoot; }

    public boolean hasGrammar() { return grammarStr != null && !grammarStr.isEmpty(); }

    float temperature() { return temperature != null ? temperature : 0.0f; }
    int topK() { return topK != null ? topK : 40; }
    float topP() { return topP != null ? topP : 0.95f; }
    float minP() { return minP != null ? minP : 0.05f; }
    float penaltyRepeat() { return penaltyRepeat != null ? penaltyRepeat : 1.0f; }
    float penaltyFreq() { return penaltyFreq != null ? penaltyFreq : 0.0f; }
    float penaltyPresent() { return penaltyPresent != null ? penaltyPresent : 0.0f; }
    int penaltyLastN() { return penaltyLastN != null ? penaltyLastN : 64; }
    ThinkingMode effectiveThinkingMode() {
        if (enableThinking != null) {
            return enableThinking ? ThinkingMode.ENABLED : ThinkingMode.DISABLED;
        }
        return thinkingMode != null ? thinkingMode : ThinkingMode.AUTO;
    }
    Map<String, String> chatTemplateKwargs() { return chatTemplateKwargs != null ? chatTemplateKwargs : Collections.emptyMap(); }

    private static void requireFiniteRange(float value, float min, float max, String option) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(option + " must be finite and between " + min + " and " + max);
        }
    }

    private static void requireNonNegative(int value, String option) {
        if (value < 0) {
            throw new IllegalArgumentException(option + " cannot be negative");
        }
    }
}
