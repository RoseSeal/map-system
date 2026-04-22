package com.whut.map.map_service.source.weather.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
public class WeatherMessageHandler implements MqttCallback {

    private static final Set<String> KNOWN_WEATHER_CODES = Set.of("CLEAR", "FOG", "RAIN", "SNOW", "STORM");

    private final ObjectMapper objectMapper;
    private final WeatherContextHolder weatherContextHolder;

    public WeatherMessageHandler(ObjectMapper objectMapper, WeatherContextHolder weatherContextHolder) {
        this.objectMapper = objectMapper;
        this.weatherContextHolder = weatherContextHolder;
    }

    @Override
    public void connectionLost(Throwable cause) {
        String message = cause == null ? "unknown" : cause.getMessage();
        log.info("Weather MQTT connection lost: {}", message);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            WeatherMqttDto weatherMqttDto = objectMapper.readValue(payload, WeatherMqttDto.class);
            Instant snapshotTime = Instant.now();
            WeatherContext context = toContext(weatherMqttDto, snapshotTime);
            List<WeatherZoneContext> zones = toZones(weatherMqttDto, snapshotTime);
            weatherContextHolder.update(context, zones);
            log.debug("Weather snapshot updated, topic={}, weather_code={}, visibility_nm={}, zones={}",
                    topic,
                    context.weatherCode(),
                    context.visibilityNm(),
                    zones.size());
        } catch (Exception e) {
            log.warn("Failed to parse weather MQTT payload", e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Weather subscriber does not publish messages.
    }

    private WeatherContext toContext(WeatherMqttDto dto, Instant snapshotTime) {
        WeatherMqttDto.WindDto windDto = dto.wind();
        WeatherMqttDto.SurfaceCurrentDto currentDto = dto.surfaceCurrent();

        WeatherContext.Wind wind = new WeatherContext.Wind(
                windDto == null ? null : windDto.speedKn(),
                windDto == null ? null : windDto.directionFromDeg()
        );

        WeatherContext.SurfaceCurrent surfaceCurrent = new WeatherContext.SurfaceCurrent(
                currentDto == null ? null : currentDto.speedKn(),
                currentDto == null ? null : currentDto.setDeg()
        );

        return new WeatherContext(
                normalizeWeatherCode(dto.weatherCode()),
                dto.visibilityNm(),
                dto.precipitationMmPerHr(),
                wind,
                surfaceCurrent,
                dto.seaState(),
                snapshotTime
        );
    }

    private List<WeatherZoneContext> toZones(WeatherMqttDto dto, Instant snapshotTime) {
        List<WeatherMqttDto.WeatherZoneMqttDto> zoneDtos = dto.weatherZones();
        if (zoneDtos == null || zoneDtos.isEmpty()) {
            return Collections.emptyList();
        }
        return zoneDtos.stream()
                .filter(z -> z != null && z.zoneId() != null && z.geometry() != null)
                .map(z -> toZoneContext(z, snapshotTime))
                .collect(Collectors.toList());
    }

    private WeatherZoneContext toZoneContext(WeatherMqttDto.WeatherZoneMqttDto dto, Instant snapshotTime) {
        WeatherMqttDto.WindDto windDto = dto.wind();
        WeatherMqttDto.SurfaceCurrentDto currentDto = dto.surfaceCurrent();

        WeatherContext.Wind wind = windDto == null ? null :
                new WeatherContext.Wind(windDto.speedKn(), windDto.directionFromDeg());
        WeatherContext.SurfaceCurrent surfaceCurrent = currentDto == null ? null :
                new WeatherContext.SurfaceCurrent(currentDto.speedKn(), currentDto.setDeg());

        WeatherMqttDto.WeatherZoneMqttDto.ZoneGeometryDto geomDto = dto.geometry();
        WeatherZoneContext.ZoneGeometry geometry = geomDto == null ? null :
                new WeatherZoneContext.ZoneGeometry(geomDto.type(), geomDto.coordinates());

        return new WeatherZoneContext(
                dto.zoneId(),
                normalizeWeatherCode(dto.weatherCode()),
                dto.visibilityNm(),
                dto.precipitationMmPerHr(),
                wind,
                surfaceCurrent,
                dto.seaState(),
                snapshotTime,
                geometry
        );
    }

    private String normalizeWeatherCode(String weatherCode) {
        if (weatherCode == null || weatherCode.isBlank()) {
            return null;
        }

        String normalized = weatherCode.trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_WEATHER_CODES.contains(normalized)) {
            log.warn("Unknown weather_code received: {}", normalized);
        }

        return normalized;
    }
}
