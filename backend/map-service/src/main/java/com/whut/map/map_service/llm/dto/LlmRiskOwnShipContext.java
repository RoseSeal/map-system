package com.whut.map.map_service.llm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmRiskOwnShipContext {
    private String id;
    private double longitude;
    private double latitude;
    private double sog;
    private double cog;
    private Double heading;
    private Double confidence;
}
