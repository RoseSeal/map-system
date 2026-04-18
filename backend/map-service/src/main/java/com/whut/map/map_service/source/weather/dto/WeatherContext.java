package com.whut.map.map_service.source.weather.dto;

import java.time.Instant;

public record WeatherContext(
        String weatherCode,
        Double visibilityNm,
        Double precipitationMmPerHr,
        Wind wind,
        SurfaceCurrent surfaceCurrent,
        Integer seaState,
        Instant updatedAt
) {

    public WeatherContext withUpdatedAt(Instant instant) {
        return new WeatherContext(
                weatherCode,
                visibilityNm,
                precipitationMmPerHr,
                wind,
                surfaceCurrent,
                seaState,
                instant
        );
    }

    public record Wind(
            Double speedKn,
            Double directionFromDeg
    ) {
    }

    public record SurfaceCurrent(
            Double speedKn,
            Double setDeg
    ) {
    }
}
