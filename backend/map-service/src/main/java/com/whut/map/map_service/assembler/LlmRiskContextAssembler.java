package com.whut.map.map_service.assembler;

import com.whut.map.map_service.domain.RiskLevel;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class LlmRiskContextAssembler {
    public LlmRiskContext assemble(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, Double> currentDistancesNm,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        if (ownShip == null) {
            return null;
        }

        return LlmRiskContext.builder()
                .ownShip(buildOwnShipContext(ownShip))
                .targets(buildTargetContexts(ownShip, allShips, currentDistancesNm, cpaResults, riskResult))
                .build();
    }

    private LlmRiskOwnShipContext buildOwnShipContext(ShipStatus ownShip) {
        return LlmRiskOwnShipContext.builder()
                .id(ownShip.getId())
                .longitude(ownShip.getLongitude())
                .latitude(ownShip.getLatitude())
                .sog(ownShip.getSog())
                .cog(ownShip.getCog())
                .heading(ownShip.getHeading())
                .confidence(ownShip.getConfidence())
                .build();
    }

    private List<LlmRiskTargetContext> buildTargetContexts(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, Double> currentDistancesNm,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        List<LlmRiskTargetContext> targets = new ArrayList<>();
        if (allShips == null) {
            return targets;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }

            TargetRiskAssessment assessment = riskResult == null ? null : riskResult.getTargetAssessment(ship.getId());
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());

            targets.add(LlmRiskTargetContext.builder()
                    .targetId(ship.getId())
                    .riskLevel(resolveRiskLevel(assessment))
                    .currentDistanceNm(resolveCurrentDistanceNm(ship.getId(), currentDistancesNm))
                    .relativeBearingDeg(resolveRelativeBearingDeg(ownShip, ship))
                    .dcpaNm(GeoUtils.metersToNm(assessment == null ? 0.0 : assessment.getCpaDistanceMeters()))
                    .tcpaSec(assessment == null ? 0.0 : assessment.getTcpaSeconds())
                    .approaching(cpaResult != null && cpaResult.isApproaching())
                    .longitude(ship.getLongitude())
                    .latitude(ship.getLatitude())
                    .speedKn(ship.getSog())
                    .courseDeg(ship.getCog())
                    .confidence(ship.getConfidence())
                    .ruleExplanation(assessment == null ? null : assessment.getExplanationText())
                    .build());
        }

        return targets;
    }

    private RiskLevel resolveRiskLevel(TargetRiskAssessment assessment) {
        return assessment == null ? null : RiskLevel.fromValue(assessment.getRiskLevel());
    }

    private Double resolveRelativeBearingDeg(ShipStatus ownShip, ShipStatus targetShip) {
        if (ownShip == null || targetShip == null) {
            return null;
        }
        double trueBearing = GeoUtils.trueBearing(
                ownShip.getLatitude(),
                ownShip.getLongitude(),
                targetShip.getLatitude(),
                targetShip.getLongitude()
        );
        double referenceHeading = ownShip.getHeading() != null ? ownShip.getHeading() : ownShip.getCog();
        return (trueBearing - referenceHeading + 360.0) % 360.0;
    }

    private Double resolveCurrentDistanceNm(String targetId, Map<String, Double> currentDistancesNm) {
        if (targetId == null || currentDistancesNm == null) {
            return null;
        }
        return currentDistancesNm.get(targetId);
    }
}
