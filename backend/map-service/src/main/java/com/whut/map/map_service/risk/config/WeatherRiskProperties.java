package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk.weather")
public class WeatherRiskProperties {

    private VisibilityConfig visibility = new VisibilityConfig();
    private StormConfig storm = new StormConfig();

    @Data
    public static class VisibilityConfig {
        private boolean enabled = false;
        private double lowVisNm = 2.0;
        private double veryLowVisNm = 0.5;
        private double scaleLow = 1.5;
        private double scaleVeryLow = 2.0;
    }

    @Data
    public static class StormConfig {
        private boolean enabled = false;
        private double penaltyScore = 0.15;
        private int seaStateThreshold = 7;
    }
}
