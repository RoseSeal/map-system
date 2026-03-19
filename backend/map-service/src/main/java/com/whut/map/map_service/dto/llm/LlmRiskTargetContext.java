package com.whut.map.map_service.dto.llm;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmRiskTargetContext {
    private String targetId;
    private String riskLevel;
    private double dcpaNm;
    private double tcpaSec;
    private boolean approaching;
    private double longitude;
    private double latitude;
    private double speedKn;
    private double courseDeg;
    private Double confidence;
}
