package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.config.properties.RiskAssessmentProperties;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class RiskAssessmentEngine {
    /**
     * Ships within this many seconds of CPA (on either side) are treated as at the closest point
     * of approach and classified by DCPA alone. Prevents a sudden drop to SAFE at the exact
     * TCPA == 0 instant due to floating-point boundary effects.
     */
    private static final double TCPA_CPA_EPS_SEC = 1.0;

    private final RiskAssessmentProperties riskProperties;

    public RiskAssessmentEngine(RiskAssessmentProperties riskProperties) {
        this.riskProperties = riskProperties;
    }

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
        if (cpaResult == null) {
            return TargetRiskAssessment.builder()
                    .targetId(targetId)
                    .riskLevel(RiskConstants.SAFE)
                    .cpaDistanceMeters(0.0)
                    .tcpaSeconds(0.0)
                    .approaching(false)
                    .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                    .explanationText(RiskConstants.EXPLANATION_TEXT_AWAITING_CPA)
                    .build();
        }

        if (!cpaResult.isCpaValid()) {
            // Relative motion is near-zero (parallel / stationary ships): TCPA is undefined.
            // Current distance equals CPA distance but there is no convergence; classify as SAFE.
            return TargetRiskAssessment.builder()
                    .targetId(targetId)
                    .riskLevel(RiskConstants.SAFE)
                    .cpaDistanceMeters(cpaResult.getCpaDistance())
                    .tcpaSeconds(0.0)
                    .approaching(false)
                    .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                    .explanationText(RiskConstants.EXPLANATION_TEXT_DERIVED)
                    .build();
        }

        double cpaDistanceMeters = cpaResult.getCpaDistance();
        // Raw TCPA: negative means ships are already diverging past CPA.
        // Pass the raw value to classifyRisk so the eps boundary can distinguish
        // "just past CPA" from "clearly diverging". Clamp to >= 0 only for display.
        double rawTcpaSec = cpaResult.getTcpaTime();
        String riskLevel = classifyRisk(GeoUtils.metersToNm(cpaDistanceMeters), rawTcpaSec);

        return TargetRiskAssessment.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .cpaDistanceMeters(cpaDistanceMeters)
                .tcpaSeconds(Math.max(rawTcpaSec, 0.0))
                // "approaching" mirrors the eps boundary with >= to match classifyRisk.
                .approaching(rawTcpaSec >= -TCPA_CPA_EPS_SEC)
                .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                .explanationText(RiskConstants.EXPLANATION_TEXT_DERIVED)
                .build();
    }

    private String classifyRisk(double dcpaNm, double tcpaSec) {
        // tcpaSec >= -TCPA_CPA_EPS_SEC: approaching or at CPA (classify by DCPA + TCPA).
        // tcpaSec < -TCPA_CPA_EPS_SEC: clearly diverging past CPA, return SAFE regardless of DCPA.
        if (dcpaNm <= riskProperties.getAlarmDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getAlarmTcpaSec()) {
            return RiskConstants.ALARM;
        }
        if (dcpaNm <= riskProperties.getWarningDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getWarningTcpaSec()) {
            return RiskConstants.WARNING;
        }
        if (dcpaNm <= riskProperties.getCautionDcpaNm() && tcpaSec >= -TCPA_CPA_EPS_SEC && tcpaSec <= riskProperties.getCautionTcpaSec()) {
            return RiskConstants.CAUTION;
        }
        return RiskConstants.SAFE;
    }
}
