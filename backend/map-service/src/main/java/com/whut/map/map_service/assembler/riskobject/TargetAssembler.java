package com.whut.map.map_service.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TargetAssembler {
    private static final String TRACKING_STATUS = "tracking";

    private final RiskVisualizationAssembler riskVisualizationAssembler;

    public TargetAssembler(RiskVisualizationAssembler riskVisualizationAssembler) {
        this.riskVisualizationAssembler = riskVisualizationAssembler;
    }

    public List<Map<String, Object>> assembleTargets(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        List<Map<String, Object>> targets = new ArrayList<>();
        if (allShips == null) {
            return targets;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());
            TargetRiskAssessment assessment = riskResult == null ? null : riskResult.getTargetAssessment(ship.getId());
            targets.add(assembleTarget(ownShip, ship, cpaResult, assessment));
        }
        return targets;
    }

    public Map<String, Object> assembleTarget(
            ShipStatus ownShip,
            ShipStatus targetShip,
            CpaTcpaResult cpaResult,
            TargetRiskAssessment assessment
    ) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", targetShip.getLongitude());
        position.put("lat", targetShip.getLatitude());

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("speed_kn", targetShip.getSog());
        vector.put("course_deg", targetShip.getCog());

        Map<String, Object> cpaMetrics = new LinkedHashMap<>();
        cpaMetrics.put("dcpa_nm", GeoUtils.metersToNm(assessment == null ? 0.0 : assessment.getCpaDistanceMeters()));
        cpaMetrics.put("tcpa_sec", assessment == null ? 0.0 : assessment.getTcpaSeconds());

        String riskLevel = assessment == null ? RiskConstants.SAFE : assessment.getRiskLevel();

        Map<String, Object> riskAssessment = new LinkedHashMap<>();
        riskAssessment.put("risk_level", riskLevel);
        riskAssessment.put("cpa_metrics", cpaMetrics);

        Map<String, Object> graphicCpaLine = riskVisualizationAssembler.buildGraphicCpaLine(ownShip, targetShip, assessment);
        if (graphicCpaLine != null) {
            riskAssessment.put("graphic_cpa_line", graphicCpaLine);
        }

        Map<String, Object> oztSector = riskVisualizationAssembler.buildOztSector(targetShip, riskLevel);
        if (oztSector != null) {
            riskAssessment.put("ozt_sector", oztSector);
        }

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", targetShip.getId());
        target.put("tracking_status", TRACKING_STATUS);
        target.put("position", position);
        target.put("vector", vector);
        target.put("risk_assessment", riskAssessment);
        return target;
    }
}
