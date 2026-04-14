package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.risk.engine.encounter.EncounterType;
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
    
    @Builder.Default
    private double riskScore = 0.0;
    
    @Builder.Default
    private double riskConfidence = 1.0;
    
    private EncounterType encounterType;
    private Double domainPenetration;
}
