package com.whut.map.map_service.llm.config;

import ai.z.openapi.ZhipuAiClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class ZhipuConfig {
    private final LlmProperties llmProperties;

    @Bean
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${llm.zhipu.api-key:}')")
    public ZhipuAiClient zhipuClient() {
        // 创建并配置Zhipu客户端
        var builder = new ZhipuAiClient.Builder().ofZHIPU();
        String baseUrl = llmProperties.getZhipu().getBaseUrl();
        if (StringUtils.hasText(baseUrl)) {
            builder.baseUrl(baseUrl);
        }
        return builder
                .apiKey(llmProperties.getZhipu().getApiKey())
                .build();
    }
}
