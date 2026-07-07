package com.whut.map.map_service.risk.engine.trajectoryprediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.risk.config.TrajectoryPredictionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class TrajectoryPredictionConfig {

    private final TrajectoryPredictionProperties properties;
    private final ObjectMapper objectMapper;
    private final CvPredictionEngine cvPredictionEngine;

    @Bean
    @Primary
    @ConditionalOnProperty(
            prefix = "engine.trajectory-prediction",
            name = "learned-enabled",
            havingValue = "true"
    )
    public TrajectoryPredictionPort learningTrajectoryPredictionPort() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getLearnedTimeoutMs());
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        RestTemplate restTemplate = new RestTemplate(requestFactory);
        return new LearningTrajectoryPredictionAdapter(restTemplate, properties, objectMapper, cvPredictionEngine);
    }
}
