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
}
