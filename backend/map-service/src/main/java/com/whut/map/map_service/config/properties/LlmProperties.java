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

    private ProviderProperties gemini = new ProviderProperties();
    private ProviderProperties zhipu = new ProviderProperties();

    @Data
    public static class ProviderProperties {
        private String apiKey;
        private String model;
    }
}

