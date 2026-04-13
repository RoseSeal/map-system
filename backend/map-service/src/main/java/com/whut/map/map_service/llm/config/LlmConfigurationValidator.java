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
            case "gemini" -> requireApiKey("llm.gemini.api-key", llmProperties.getGemini().getApiKey());
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

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
