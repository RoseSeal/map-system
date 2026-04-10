package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private boolean enabled = false;
    private String provider;
    private long timeoutMs = 5000L;
    private int maxTargetsPerCall = 1;
    private int chatContextMaxTargets = 5;
    private int cooldownSeconds = 5;
    private boolean fallbackTemplateEnabled = true;
    private int conversationMaxTurns = 10;
    private long conversationTtlMinutes = 30L; // 负数表示不自动清理
    private long conversationEvictIntervalMs = 60_000L;
    private int conversationTokenBudget = 6000;

    private ProviderProperties gemini = new ProviderProperties();
    private ProviderProperties zhipu = new ProviderProperties();

    @Data
    public static class ProviderProperties {
        private String apiKey;
        private String model;
    }
}
