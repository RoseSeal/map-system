package com.whut.map.map_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "llm")
public class LlmProperties {
    private boolean enabled = false;
    private String model;
    private long timeoutMs = 1500L;
    private int maxTargetsPerCall = 3;
    private boolean fallbackTemplateEnabled = true;
    private String apiKey;
    private int cooldownSeconds = 5;
}
