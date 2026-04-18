package com.whut.map.map_service.source.weather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mqtt.weather")
public class WeatherMqttProperties {

    private String broker;
    private String clientId;

    private String username;
    private String password;

    private String topic;
}
