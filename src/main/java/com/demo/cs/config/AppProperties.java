package com.demo.cs.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置属性类（与项目一同风格：yml → 强类型对象，启动期即可发现配置错误）。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Llm llm = new Llm();
    private final Memory memory = new Memory();
    private final Mcp mcp = new Mcp();

    public Llm getLlm() { return llm; }
    public Memory getMemory() { return memory; }
    public Mcp getMcp() { return mcp; }

    public static class Llm {
        private String provider = "ollama";
        private String model = "qwen2.5:7b";
        private String baseUrl = "";
        private String apiKey = "";
        private double temperature = 0.3;
        private int timeoutSeconds = 120;
        private int maxTokens = 2048;

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    public static class Memory {
        private int maxMessages = 20;
        private int ttlDays = 7;

        public int getMaxMessages() { return maxMessages; }
        public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
        public int getTtlDays() { return ttlDays; }
        public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }
    }

    public static class Mcp {
        private boolean enabled = true;
        private String sseUrl = "http://localhost:8082/sse";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getSseUrl() { return sseUrl; }
        public void setSseUrl(String sseUrl) { this.sseUrl = sseUrl; }
    }
}
