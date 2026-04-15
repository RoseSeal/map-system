package com.whut.map.map_service.risk.engine.risk;

import lombok.Builder;
import lombok.Data;

import java.util.Collections;
import java.util.Map;

@Data
@Builder
public class RiskAssessmentResult {
    private Map<String, TargetRiskAssessment> targetAssessments;

    public static RiskAssessmentResult empty() {
        return RiskAssessmentResult.builder()
                .targetAssessments(Collections.emptyMap())
                .build();
    }

    public TargetRiskAssessment getTargetAssessment(String targetId) {
        if (targetAssessments == null) {
            return null;
        }
        return targetAssessments.get(targetId);
    }
}
