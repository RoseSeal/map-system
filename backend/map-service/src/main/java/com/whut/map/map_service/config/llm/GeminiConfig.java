package com.whut.map.map_service.config.llm;

import com.google.genai.Client;
import com.whut.map.map_service.config.properties.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiConfig {

    private final LlmProperties llmProperties;

    @Bean
    public Client geminiClient() {
        // 创建并配置Gemini客户端
        return new Client.Builder()
                .apiKey(llmProperties.getGemini().getApiKey())
                .build();
    }
}

