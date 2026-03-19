package com.whut.map.map_service.dto.llm;

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
    private Double confidence;
}
