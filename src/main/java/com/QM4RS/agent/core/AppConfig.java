package com.QM4RS.agent.core;

public class AppConfig {
    private String apiKey = "";
    private String model = "gpt-5.2";

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey.trim(); }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model == null ? "gpt-4.1" : model.trim(); }
}
