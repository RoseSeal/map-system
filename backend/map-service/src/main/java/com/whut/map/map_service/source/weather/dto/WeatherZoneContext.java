package com.whut.map.map_service.source.weather.dto;

import java.time.Instant;

public record WeatherZoneContext(
        String zoneId,
        String weatherCode,
        Double visibilityNm,
        Double precipitationMmPerHr,
        WeatherContext.Wind wind,
        WeatherContext.SurfaceCurrent surfaceCurrent,
        Integer seaState,
        Instant updatedAt,
        ZoneGeometry geometry
) {
    public record ZoneGeometry(String type, Object coordinates) {}
}

