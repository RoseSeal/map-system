package com.whut.map.map_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ais")
public class AisProperties {

    private String ownShipMmsi;
    private String timezone;
    private String dateFormat;
    private double confidence;

}
