package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.RiskConstants;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RiskVisualizationAssembler {

    public Map<String, Object> buildGraphicCpaLine(
            ShipStatus ownShip,
            ShipStatus targetShip,
            TargetRiskAssessment assessment,
            PredictedCpaTcpaResult predictedCpaResult
    ) {
        if (assessment == null || !assessment.isApproaching()) {
            return null;
        }

        if (predictedCpaResult != null) {
            Map<String, Object> graphicCpaLine = new LinkedHashMap<>();
            graphicCpaLine.put("own_pos", List.of(
                    predictedCpaResult.getOwnCpaLongitude(),
                    predictedCpaResult.getOwnCpaLatitude()
            ));
            graphicCpaLine.put("target_pos", List.of(
                    predictedCpaResult.getTargetCpaLongitude(),
                    predictedCpaResult.getTargetCpaLatitude()
            ));
            return graphicCpaLine;
        }

        double tcpaSeconds = Math.max(assessment.getTcpaSeconds(), 0.0);
        double[] ownVelocity = GeoUtils.toVelocity(ownShip.getSog(), ownShip.getCog());
        double[] targetVelocity = GeoUtils.toVelocity(targetShip.getSog(), targetShip.getCog());
        double[] ownCpaPos = GeoUtils.displace(
                ownShip.getLatitude(),
                ownShip.getLongitude(),
                ownVelocity[0] * tcpaSeconds,
                ownVelocity[1] * tcpaSeconds
        );
        double[] targetCpaPos = GeoUtils.displace(
                targetShip.getLatitude(),
                targetShip.getLongitude(),
                targetVelocity[0] * tcpaSeconds,
                targetVelocity[1] * tcpaSeconds
        );

        Map<String, Object> graphicCpaLine = new LinkedHashMap<>();
        graphicCpaLine.put("own_pos", List.of(ownCpaPos[1], ownCpaPos[0]));
        graphicCpaLine.put("target_pos", List.of(targetCpaPos[1], targetCpaPos[0]));
        return graphicCpaLine;
    }

    public Map<String, Object> buildOztSector(ShipStatus targetShip, String riskLevel, EncounterType encounterType) {
        if (!RiskConstants.WARNING.equals(riskLevel) && !RiskConstants.ALARM.equals(riskLevel)) {
            return null;
        }

        double halfAngle = resolveOztHalfAngle(encounterType);
        Map<String, Object> oztSector = new LinkedHashMap<>();
        oztSector.put("start_angle_deg", targetShip.getCog() - halfAngle);
        oztSector.put("end_angle_deg", targetShip.getCog() + halfAngle);
        oztSector.put("is_active", true);
        return oztSector;
    }

    private double resolveOztHalfAngle(EncounterType encounterType) {
        if (encounterType == null) {
            return 10.0;
        }
        return switch (encounterType) {
            case HEAD_ON -> 20.0;
            case OVERTAKING -> 8.0;
            case CROSSING -> 12.0;
            default -> 10.0;  // covers UNDEFINED: conservative fallback when encounter geometry is indeterminate
        };
    }
}
