package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.trajectory-prediction")
public class TrajectoryPredictionProperties {
    private int horizonSeconds = 240;
    private int stepSeconds = 30;
    private double rotThresholdDegPerMin = 1.5;
}
