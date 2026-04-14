package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.risk-meta")
public class RiskObjectMetaProperties {

    private String governanceMode = "adaptive";
    private double safetyContourVal = 10.0;
}