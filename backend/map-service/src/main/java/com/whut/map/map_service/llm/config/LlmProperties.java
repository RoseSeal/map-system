package com.whut.map.map_service.llm.config;

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
    private Advisory advisory = new Advisory();

    @Data
    public static class ProviderProperties {
        private String apiKey;
        private String model;
        private ProxyProperties proxy = new ProxyProperties();
        private RetryProperties retry = new RetryProperties();
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String host;
        private Integer port;
        private String scheme = "http";
    }

    @Data
    public static class RetryProperties {
        private int maxRetries = 2;
        private long initialBackoffMs = 1000L;
    }

    @Data
    public static class Advisory {
        private boolean enabled = false;
        private int tcpaThresholdSeconds = 300;
        private int snapshotStalenessThreshold = 5;
    }
}
