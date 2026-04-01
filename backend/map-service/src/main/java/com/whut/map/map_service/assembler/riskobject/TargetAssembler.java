package com.whut.map.map_service.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TargetAssembler {
    private static final double METERS_PER_NAUTICAL_MILE = 1852.0;
    private static final String TRACKING_STATUS = "tracking";

    private final RiskVisualizationAssembler riskVisualizationAssembler;

    public TargetAssembler(RiskVisualizationAssembler riskVisualizationAssembler) {
        this.riskVisualizationAssembler = riskVisualizationAssembler;
    }

    public List<Map<String, Object>> assembleTargets(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult,
            Map<String, LlmExplanation> llmExplanations
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
            LlmExplanation llmExplanation = llmExplanations == null ? null : llmExplanations.get(ship.getId());
            targets.add(assembleTarget(ownShip, ship, cpaResult, assessment, llmExplanation));
        }
        return targets;
    }

    public Map<String, Object> assembleTarget(
            ShipStatus ownShip,
            ShipStatus targetShip,
            CpaTcpaResult cpaResult,
            TargetRiskAssessment assessment,
            LlmExplanation llmExplanation
    ) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", targetShip.getLongitude());
        position.put("lat", targetShip.getLatitude());

        Map<String, Object> vector = new LinkedHashMap<>();
        vector.put("speed_kn", targetShip.getSog());
        vector.put("course_deg", targetShip.getCog());

        Map<String, Object> cpaMetrics = new LinkedHashMap<>();
        cpaMetrics.put("dcpa_nm", toNm(assessment == null ? 0.0 : assessment.getCpaDistanceMeters()));
        cpaMetrics.put("tcpa_sec", assessment == null ? 0.0 : assessment.getTcpaSeconds());

        String riskLevel = assessment == null ? RiskConstants.SAFE : assessment.getRiskLevel();

        Map<String, Object> riskAssessment = new LinkedHashMap<>();
        riskAssessment.put("risk_level", riskLevel);
        riskAssessment.put("cpa_metrics", cpaMetrics);

        Map<String, Object> graphicCpaLine = riskVisualizationAssembler.buildGraphicCpaLine(ownShip, targetShip, cpaResult);
        if (graphicCpaLine != null) {
            riskAssessment.put("graphic_cpa_line", graphicCpaLine);
        }

        Map<String, Object> oztSector = riskVisualizationAssembler.buildOztSector(targetShip, riskLevel);
        if (oztSector != null) {
            riskAssessment.put("ozt_sector", oztSector);
        }

        Map<String, Object> explanation = new LinkedHashMap<>();
        explanation.put("source", resolveExplanationSource(assessment, llmExplanation));
        explanation.put("text", resolveExplanationText(assessment, llmExplanation));
        riskAssessment.put("explanation", explanation);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("id", targetShip.getId());
        target.put("tracking_status", TRACKING_STATUS);
        target.put("position", position);
        target.put("vector", vector);
        target.put("risk_assessment", riskAssessment);
        return target;
    }

    private String resolveExplanationSource(TargetRiskAssessment assessment, LlmExplanation llmExplanation) {
        if (llmExplanation != null && llmExplanation.getSource() != null) {
            return llmExplanation.getSource();
        }
        if (assessment != null && assessment.getExplanationSource() != null) {
            return assessment.getExplanationSource();
        }
        return RiskConstants.EXPLANATION_SOURCE_FALLBACK;
    }

    private String resolveExplanationText(TargetRiskAssessment assessment, LlmExplanation llmExplanation) {
        if (llmExplanation != null && llmExplanation.getText() != null && !llmExplanation.getText().isBlank()) {
            return llmExplanation.getText();
        }
        if (assessment != null && assessment.getExplanationText() != null && !assessment.getExplanationText().isBlank()) {
            return assessment.getExplanationText();
        }
        return RiskConstants.EXPLANATION_TEXT_AWAITING_CPA;
    }

    private double toNm(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }
}

