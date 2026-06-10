package com.whut.map.map_service.llm.agent.casegraph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.config.CaseGraphProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class CaseGraphConfig {

    private final CaseGraphProperties properties;
    private final ObjectMapper objectMapper;

    @Bean
    @ConditionalOnProperty(prefix = "llm.case-graph", name = "enabled", havingValue = "true")
    public HistoricalCaseQueryPort historicalCaseQueryPort() {
        Assert.hasText(properties.getUrl(), "llm.case-graph.url must not be blank");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getTimeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        return new LightRagCaseGraphAdapter(restTemplate, properties, objectMapper);
    }
}
