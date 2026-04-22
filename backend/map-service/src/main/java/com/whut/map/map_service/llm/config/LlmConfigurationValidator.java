package com.whut.map.map_service.llm.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class LlmConfigurationValidator {

    private final LlmProperties llmProperties;

    @PostConstruct
    void validate() {
        if (!llmProperties.isEnabled()) {
            return;
        }

        String provider = normalize(llmProperties.getProvider());
        if (!StringUtils.hasText(provider)) {
            throw new IllegalStateException("Missing required property 'llm.provider' when llm.enabled=true");
        }

        switch (provider) {
            case "gemini" -> {
                requireApiKey("llm.gemini.api-key", llmProperties.getGemini().getApiKey());
                validateProxy("llm.gemini.proxy", llmProperties.getGemini().getProxy());
                validateRetry("llm.gemini.retry", llmProperties.getGemini().getRetry());
            }
            case "zhipu" -> requireApiKey("llm.zhipu.api-key", llmProperties.getZhipu().getApiKey());
            default -> throw new IllegalStateException(
                    "Unsupported llm.provider '" + llmProperties.getProvider() + "'. Expected one of: gemini, zhipu");
        }
    }

    private void requireApiKey(String propertyName, String apiKey) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException(
                    "Missing required property '" + propertyName + "' for provider '" + llmProperties.getProvider() + "'");
        }
    }

    private void validateProxy(String propertyPrefix, LlmProperties.ProxyProperties proxyProperties) {
        if (proxyProperties == null || !proxyProperties.isEnabled()) {
            return;
        }

        if (!StringUtils.hasText(proxyProperties.getHost())) {
            throw new IllegalStateException("Missing required property '" + propertyPrefix + ".host' when proxy is enabled");
        }

        Integer port = proxyProperties.getPort();
        if (port == null || port <= 0 || port > 65_535) {
            throw new IllegalStateException("Property '" + propertyPrefix + ".port' must be between 1 and 65535 when proxy is enabled");
        }

        if (!StringUtils.hasText(proxyProperties.getScheme())) {
            throw new IllegalStateException("Missing required property '" + propertyPrefix + ".scheme' when proxy is enabled");
        }
    }

    private void validateRetry(String propertyPrefix, LlmProperties.RetryProperties retryProperties) {
        if (retryProperties == null) {
            return;
        }

        if (retryProperties.getMaxRetries() < 0 || retryProperties.getMaxRetries() > 2) {
            throw new IllegalStateException("Property '" + propertyPrefix + ".max-retries' must be between 0 and 2");
        }

        if (retryProperties.getMaxRetries() > 0 && retryProperties.getInitialBackoffMs() < 1000L) {
            throw new IllegalStateException("Property '" + propertyPrefix + ".initial-backoff-ms' must be at least 1000 when retries are enabled");
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
