package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.shared.domain.QualityFlag;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.RiskConstants;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.risk.pipeline.assembler.AssemblerProtocolConstants;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class TargetAssembler {
    private final RiskVisualizationAssembler riskVisualizationAssembler;

    public TargetAssembler(RiskVisualizationAssembler riskVisualizationAssembler) {
        this.riskVisualizationAssembler = riskVisualizationAssembler;
    }

    public List<Map<String, Object>> assembleTargets(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult,
            Map<String, CvPredictionResult> cvResults,
            Map<String, EncounterClassificationResult> encounterResults
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
            CvPredictionResult cvResult = (cvResults == null) ? null : cvResults.get(ship.getId());
            EncounterClassificationResult encounterResult = encounterResults == null ? null : encounterResults.get(ship.getId());
            targets.add(assembleTarget(ownShip, ship, cpaResult, assessment, cvResult, encounterResult));
        }
        return targets;
    }

    public Map<String, Object> assembleTarget(
            ShipStatus ownShip,
            ShipStatus targetShip,
            CpaTcpaResult cpaResult,
            TargetRiskAssessment assessment,
            CvPredictionResult cvResult,
            EncounterClassificationResult encounterResult
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
        
        // Ensure core scoring fields are always present for protocol stability
        riskAssessment.put("risk_score", assessment != null ? assessment.getRiskScore() : 0.0);
        riskAssessment.put("risk_confidence", assessment != null ? assessment.getRiskConfidence() : 1.0);

        Map<String, Object> graphicCpaLine = riskVisualizationAssembler.buildGraphicCpaLine(ownShip, targetShip, assessment);
        if (graphicCpaLine != null) {
            riskAssessment.put("graphic_cpa_line", graphicCpaLine);
        }

        EncounterType encounterType = encounterResult == null ? null : encounterResult.getEncounterType();
        Map<String, Object> oztSector = riskVisualizationAssembler.buildOztSector(targetShip, riskLevel, encounterType);
        if (oztSector != null) {
            riskAssessment.put("ozt_sector", oztSector);
        }

        if (encounterType != null) {
            riskAssessment.put("encounter_type", encounterType.name());
        }

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", targetShip.getId());
        target.put("tracking_status", resolveTrackingStatus(targetShip.getQualityFlags()));
        target.put("position", position);
        target.put("vector", vector);
        target.put("risk_assessment", riskAssessment);

        if (cvResult != null && cvResult.getTrajectory() != null && !cvResult.getTrajectory().isEmpty()) {
            target.put("predicted_trajectory", buildPredictedTrajectory(cvResult));
        }

        return target;
    }

    private Map<String, Object> buildPredictedTrajectory(CvPredictionResult cvResult) {
        List<Map<String, Object>> points = new ArrayList<>();
        for (CvPredictionResult.PredictedPoint p : cvResult.getTrajectory()) {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("lat", p.getLatitude());
            point.put("lon", p.getLongitude());
            point.put("offset_seconds", p.getOffsetSeconds());
            points.add(point);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prediction_type", AssemblerProtocolConstants.PREDICTION_TYPE_CV);
        result.put("horizon_seconds", cvResult.getHorizonSeconds());
        result.put("points", points);
        return result;
    }

    private String resolveTrackingStatus(Set<QualityFlag> flags) {
        if (flags != null && flags.contains(QualityFlag.MISSING_TIMESTAMP)) {
            return AssemblerProtocolConstants.TRACKING_STATUS_STALE;
        }
        return AssemblerProtocolConstants.TRACKING_STATUS_TRACKING;
    }
}
