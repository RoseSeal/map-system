package com.whut.map.map_service.llm.dto;

import com.whut.map.map_service.domain.RiskLevel;
import com.whut.map.map_service.engine.encounter.EncounterType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmRiskTargetContext {
    private String targetId;
    private RiskLevel riskLevel;
    private Double currentDistanceNm;
    private Double relativeBearingDeg;
    private double dcpaNm;
    private double tcpaSec;
    private boolean approaching;
    private double longitude;
    private double latitude;
    private double speedKn;
    private double courseDeg;
    private Double confidence;
    private Double riskScore;
    private Double domainPenetration;
    private String ruleExplanation;
    private EncounterType encounterType;
}
