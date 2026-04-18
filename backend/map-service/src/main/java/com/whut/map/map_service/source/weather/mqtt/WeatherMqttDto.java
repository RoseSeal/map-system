package com.whut.map.map_service.source.weather.mqtt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WeatherMqttDto(
        @JsonProperty("weather_code")
        String weatherCode,

        @JsonProperty("visibility_nm")
        Double visibilityNm,

        @JsonProperty("precipitation_mm_per_hr")
        Double precipitationMmPerHr,

        @JsonProperty("wind")
        WindDto wind,

        @JsonProperty("surface_current")
        SurfaceCurrentDto surfaceCurrent,

        @JsonProperty("sea_state")
        Integer seaState,

        @JsonProperty("timestamp_utc")
        Instant timestampUtc
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WindDto(
            @JsonProperty("speed_kn")
            Double speedKn,

            @JsonProperty("direction_from_deg")
            Double directionFromDeg
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SurfaceCurrentDto(
            @JsonProperty("speed_kn")
            Double speedKn,

            @JsonProperty("set_deg")
            Double setDeg
    ) {
    }
}
