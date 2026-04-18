package com.whut.map.map_service.source.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.risk-meta.weather-alert")
public class WeatherAlertProperties {

    private double lowVisibilityNm = 2.0;
    private double highWindKn = 25.0;
    private double heavyPrecipitationMmPerHr = 10.0;
    private double strongCurrentSetKn = 2.5;
    private long staleThresholdSeconds = 60;
}
