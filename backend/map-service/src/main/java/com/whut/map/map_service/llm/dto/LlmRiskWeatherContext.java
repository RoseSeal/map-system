package com.whut.map.map_service.llm.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LlmRiskWeatherContext {
    private String weatherCode;
    private Double visibilityNm;
    private Double windSpeedKn;
    private Integer windDirectionFromDeg;
    private Double surfaceCurrentSpeedKn;
    private Integer surfaceCurrentSetDeg;
    private Integer seaState;
    private String sourceZoneId;
    private List<String> activeAlerts;
}
