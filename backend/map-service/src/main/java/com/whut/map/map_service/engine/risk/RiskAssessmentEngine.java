package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class RiskAssessmentEngine {
    private static final double ALARM_DCPA_NM = 0.3;
    private static final double WARNING_DCPA_NM = 0.5;
    private static final double CAUTION_DCPA_NM = 1.0;
    private static final double ALARM_TCPA_SEC = 300.0;
    private static final double WARNING_TCPA_SEC = 900.0;
    private static final double CAUTION_TCPA_SEC = 1800.0;
    private static final double METERS_PER_NAUTICAL_MILE = 1852.0;

    public RiskAssessmentResult consume(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            ShipDomainResult shipDomainResult,
            CvPredictionResult cvPredictionResult
    ) {
        if (ownShip == null || allShips == null) {
            return RiskAssessmentResult.empty();
        }

        log.debug("Aggregating risk assessment for ownShip={}, targets={}", ownShip.getId(), allShips.size());

        Map<String, TargetRiskAssessment> assessments = new LinkedHashMap<>();
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());
            TargetRiskAssessment assessment = buildTargetAssessment(ship.getId(), cpaResult);
            assessments.put(ship.getId(), assessment);
        }

        return RiskAssessmentResult.builder()
                .targetAssessments(assessments)
                .build();
    }

    private TargetRiskAssessment buildTargetAssessment(String targetId, CpaTcpaResult cpaResult) {
        double cpaDistanceMeters = cpaResult == null ? 0.0 : cpaResult.getCpaDistance();
        double tcpaSeconds = cpaResult == null ? 0.0 : Math.max(cpaResult.getTcpaTime(), 0.0);
        String riskLevel = classifyRisk(metersToNm(cpaDistanceMeters), tcpaSeconds);

        return TargetRiskAssessment.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .cpaDistanceMeters(cpaDistanceMeters)
                .tcpaSeconds(tcpaSeconds)
                .approaching(cpaResult != null && cpaResult.isApproaching())
                .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                .explanationText(cpaResult == null ? RiskConstants.EXPLANATION_TEXT_AWAITING_CPA : RiskConstants.EXPLANATION_TEXT_DERIVED)
                .build();
    }

    private String classifyRisk(double dcpaNm, double tcpaSec) {
        if (dcpaNm <= ALARM_DCPA_NM && tcpaSec > 0 && tcpaSec <= ALARM_TCPA_SEC) {
            return RiskConstants.ALARM;
        }
        if (dcpaNm <= WARNING_DCPA_NM && tcpaSec > 0 && tcpaSec <= WARNING_TCPA_SEC) {
            return RiskConstants.WARNING;
        }
        if (dcpaNm <= CAUTION_DCPA_NM && tcpaSec > 0 && tcpaSec <= CAUTION_TCPA_SEC) {
            return RiskConstants.CAUTION;
        }
        return RiskConstants.SAFE;
    }

    private double metersToNm(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }
}
