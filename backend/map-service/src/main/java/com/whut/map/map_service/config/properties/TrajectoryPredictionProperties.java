package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.trajectory-prediction")
public class TrajectoryPredictionProperties {
    private int horizonSeconds = 600;
    private int stepSeconds = 30;
}
