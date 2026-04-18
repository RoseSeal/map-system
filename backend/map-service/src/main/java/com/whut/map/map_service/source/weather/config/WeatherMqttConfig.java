package com.whut.map.map_service.source.weather.config;

import com.whut.map.map_service.source.weather.mqtt.WeatherMessageHandler;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Slf4j
@Configuration
public class WeatherMqttConfig {

    private final WeatherMqttProperties mqttProperties;
    private final WeatherMessageHandler weatherMessageHandler;

    public WeatherMqttConfig(WeatherMqttProperties mqttProperties, WeatherMessageHandler weatherMessageHandler) {
        this.mqttProperties = mqttProperties;
        this.weatherMessageHandler = weatherMessageHandler;
    }

    @PostConstruct
    public void init() {
        if (isBlank(mqttProperties.getBroker()) || isBlank(mqttProperties.getTopic())) {
            log.info("Weather MQTT subscription skipped because broker/topic is missing");
            return;
        }

        try {
            MemoryPersistence memoryPersistence = new MemoryPersistence();
            MqttClient client = new MqttClient(
                    mqttProperties.getBroker(),
                    resolveClientId(),
                    memoryPersistence
            );

            MqttConnectOptions options = new MqttConnectOptions();
            if (!isBlank(mqttProperties.getUsername())) {
                options.setUserName(mqttProperties.getUsername());
            }
            if (!isBlank(mqttProperties.getPassword())) {
                options.setPassword(mqttProperties.getPassword().toCharArray());
            }
            options.setAutomaticReconnect(true);
            options.setKeepAliveInterval(60);
            options.setCleanSession(true);

            client.setCallback(weatherMessageHandler);
            client.connect(options);
            client.subscribe(mqttProperties.getTopic());

            log.info("Weather MQTT client connected to broker: {}, subscribed to topic: {}",
                    mqttProperties.getBroker(),
                    mqttProperties.getTopic());
        } catch (MqttException e) {
            log.info("Failed to connect weather MQTT client to broker: {}, error: {}",
                    mqttProperties.getBroker(),
                    e.getMessage());
        }
    }

    private String resolveClientId() {
        if (!isBlank(mqttProperties.getClientId())) {
            return mqttProperties.getClientId();
        }
        return "map-service-weather-" + UUID.randomUUID();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
