package com.whut.map.map_service.config;

import com.google.genai.Client;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class GeminiConfig {

    private final LlmProperties llmProperties;

    @Bean
    public Client geminiClient() {
        // 创建并配置Gemini客户端
        return new Client.Builder()
                .apiKey(llmProperties.getApiKey())
                .build();
    }
}
