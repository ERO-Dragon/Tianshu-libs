package com.javallamaserver.llm;

public class SamplerConfig {

    private float temperature = 0.0f;
    private int topK = 40;
    private float topP = 0.95f;
    private float minP = 0.05f;
    private float penaltyRepeat = 1.0f;
    private float penaltyFreq = 0.0f;
    private float penaltyPresent = 0.0f;
    private int penaltyLastN = 64;
    private String grammarStr = null;
    private String grammarRoot = "root";
    private boolean enableThinking = false;

    public float getTemperature() { return temperature; }
    public void setTemperature(float temperature) { this.temperature = temperature; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public float getTopP() { return topP; }
    public void setTopP(float topP) { this.topP = topP; }

    public float getMinP() { return minP; }
    public void setMinP(float minP) { this.minP = minP; }

    public float getPenaltyRepeat() { return penaltyRepeat; }
    public void setPenaltyRepeat(float penaltyRepeat) { this.penaltyRepeat = penaltyRepeat; }

    public float getPenaltyFreq() { return penaltyFreq; }
    public void setPenaltyFreq(float penaltyFreq) { this.penaltyFreq = penaltyFreq; }

    public float getPenaltyPresent() { return penaltyPresent; }
    public void setPenaltyPresent(float penaltyPresent) { this.penaltyPresent = penaltyPresent; }

    public int getPenaltyLastN() { return penaltyLastN; }
    public void setPenaltyLastN(int penaltyLastN) { this.penaltyLastN = penaltyLastN; }

    public String getGrammarStr() { return grammarStr; }
    public void setGrammarStr(String grammarStr) { this.grammarStr = grammarStr; }

    public String getGrammarRoot() { return grammarRoot; }
    public void setGrammarRoot(String grammarRoot) { this.grammarRoot = grammarRoot; }

    public boolean hasGrammar() { return grammarStr != null && !grammarStr.isEmpty(); }

    public boolean isEnableThinking() { return enableThinking; }
    public void setEnableThinking(boolean enableThinking) { this.enableThinking = enableThinking; }
}
