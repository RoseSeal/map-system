package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.trajectory-prediction")
public class TrajectoryPredictionProperties {
    private int horizonSeconds = 120;
    private int stepSeconds = 10;
    private double rotThresholdDegPerMin = 0.5;
    private boolean learnedEnabled = false;
    private String learnedUrl = "http://127.0.0.1:8200";
    private long learnedTimeoutMs = 250L;
    private int learnedSampleIntervalSeconds = 150;
    private int learnedWindowSize = 10;
    private int learnedCircuitBreakerFailureThreshold = 5;
    private int learnedCircuitBreakerOpenSeconds = 30;
    private int learnedHorizonSeconds = 460;
    private int learnedRawBufferMaxPoints = 5000;
}
