package com.whut.map.map_service.engine.risk;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TargetRiskAssessment {
    private String targetId;
    private String riskLevel;
    private double cpaDistanceMeters;
    private double tcpaSeconds;
    private boolean approaching;
    private String explanationSource;
    private String explanationText;
}
