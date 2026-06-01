package com.rheinmetal.tianshu.libs.llm;

public class SamplerConfig {
    public Float temperature;
    public Integer topK;
    public Float topP;
    public Float minP;
    public Float penaltyRepeat;
    public Float penaltyFreq;
    public Float penaltyPresent;
    public Integer penaltyLastN;
    public Boolean enableThinking;
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
        this.enableThinking = false;
        this.grammarRoot = "root";
    }

    public static SamplerConfig defaults() {
        return new SamplerConfig();
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

    public Boolean getEnableThinking() { return enableThinking; }
    public void setEnableThinking(Boolean enableThinking) { this.enableThinking = enableThinking; }

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
    boolean enableThinking() { return Boolean.TRUE.equals(enableThinking); }
}