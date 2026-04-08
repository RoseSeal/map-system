package com.whut.map.map_service.llm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmRiskTargetContext {
    private String targetId;
    private String riskLevel;
    private Double currentDistanceNm;
    private double dcpaNm;
    private double tcpaSec;
    private boolean approaching;
    private double longitude;
    private double latitude;
    private double speedKn;
    private double courseDeg;
    private Double confidence;
    private String ruleExplanation;
}
