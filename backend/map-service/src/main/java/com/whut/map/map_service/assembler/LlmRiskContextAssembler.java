package com.whut.map.map_service.assembler;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import com.whut.map.map_service.dto.llm.LlmRiskOwnShipContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class LlmRiskContextAssembler {
    private static final double METERS_PER_NAUTICAL_MILE = 1852.0;

    public LlmRiskContext assemble(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        if (ownShip == null) {
            return null;
        }

        return LlmRiskContext.builder()
                .ownShip(buildOwnShipContext(ownShip))
                .targets(buildTargetContexts(ownShip, allShips, cpaResults, riskResult))
                .build();
    }

    private LlmRiskOwnShipContext buildOwnShipContext(ShipStatus ownShip) {
        return LlmRiskOwnShipContext.builder()
                .id(ownShip.getId())
                .longitude(ownShip.getLongitude())
                .latitude(ownShip.getLatitude())
                .sog(ownShip.getSog())
                .cog(ownShip.getCog())
                .confidence(ownShip.getConfidence())
                .build();
    }

    private List<LlmRiskTargetContext> buildTargetContexts(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
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
                    .riskLevel(assessment == null ? null : assessment.getRiskLevel())
                    .dcpaNm(toNm(assessment == null ? 0.0 : assessment.getCpaDistanceMeters()))
                    .tcpaSec(assessment == null ? 0.0 : assessment.getTcpaSeconds())
                    .approaching(cpaResult != null && cpaResult.isApproaching())
                    .longitude(ship.getLongitude())
                    .latitude(ship.getLatitude())
                    .speedKn(ship.getSog())
                    .courseDeg(ship.getCog())
                    .confidence(ship.getConfidence())
                    .build());
        }

        return targets;
    }

    private double toNm(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }
}
