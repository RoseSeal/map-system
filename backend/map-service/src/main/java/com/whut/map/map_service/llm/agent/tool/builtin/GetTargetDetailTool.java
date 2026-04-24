package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.util.GeoUtils;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.springframework.stereotype.Component;

@Component
public class GetTargetDetailTool implements AgentTool {

    private final ObjectMapper mapper;
    private final ToolDefinition definition;

    public GetTargetDetailTool(ObjectMapper mapper) {
        this.mapper = mapper;
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("target_id")
                .put("type", "string")
                .put("description", "Target ship identifier");
        schema.putArray("required").add("target_id");
        this.definition = new ToolDefinition(
                AgentToolNames.GET_TARGET_DETAIL,
                "Returns full detail for a single target ship: current state, CPA/TCPA data, encounter classification, risk assessment, and trajectory prediction.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        if (!call.arguments().has("target_id") || call.arguments().get("target_id").isNull()) {
            return errorResult(call, "INVALID_ARGUMENT", "Required field 'target_id' is missing");
        }
        String targetId = call.arguments().get("target_id").asText();
        if (targetId.isBlank()) {
            return errorResult(call, "INVALID_ARGUMENT", "Field 'target_id' must not be blank");
        }

        LlmRiskTargetContext lightCtx = null;
        if (snapshot.riskContext() != null && snapshot.riskContext().getTargets() != null) {
            lightCtx = snapshot.riskContext().getTargets().stream()
                    .filter(t -> targetId.equals(t.getTargetId()))
                    .findFirst().orElse(null);
        }
        TargetDerivedSnapshot derived = snapshot.targetDetails() != null
                ? snapshot.targetDetails().get(targetId) : null;

        if (lightCtx == null && derived == null) {
            return errorResult(call, "TARGET_NOT_FOUND",
                    "Target " + targetId + " is not present in snapshot_version " + snapshot.snapshotVersion());
        }

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());

        buildTargetSection(payload, lightCtx);
        buildDerivedSection(payload, derived);

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private void buildTargetSection(ObjectNode payload, LlmRiskTargetContext t) {
        if (t == null) {
            payload.putNull("target");
            return;
        }
        ObjectNode node = payload.putObject("target")
                .put("target_id", t.getTargetId())
                .put("approaching", t.isApproaching())
                .put("longitude", t.getLongitude())
                .put("latitude", t.getLatitude())
                .put("speed_kn", t.getSpeedKn())
                .put("course_deg", t.getCourseDeg())
                .put("dcpa_nm", t.getDcpaNm())
                .put("tcpa_sec", t.getTcpaSec());
        if (t.getRiskLevel() != null) node.put("risk_level", t.getRiskLevel().name());
        else node.putNull("risk_level");
        if (t.getRiskScore() != null) node.put("risk_score", t.getRiskScore());
        else node.putNull("risk_score");
        if (t.getCurrentDistanceNm() != null) node.put("current_distance_nm", t.getCurrentDistanceNm());
        else node.putNull("current_distance_nm");
        if (t.getRelativeBearingDeg() != null) node.put("relative_bearing_deg", t.getRelativeBearingDeg());
        else node.putNull("relative_bearing_deg");
        if (t.getConfidence() != null) node.put("confidence", t.getConfidence());
        else node.putNull("confidence");
        if (t.getDomainPenetration() != null) node.put("domain_penetration", t.getDomainPenetration());
        else node.putNull("domain_penetration");
        node.put("rule_explanation", t.getRuleExplanation());
        if (t.getEncounterType() != null) node.put("encounter_type", t.getEncounterType().name());
        else node.putNull("encounter_type");
    }

    private void buildDerivedSection(ObjectNode payload, TargetDerivedSnapshot derived) {
        if (derived == null) {
            payload.putNull("derived");
            return;
        }
        ObjectNode derivedNode = payload.putObject("derived");

        buildCpaNode(derivedNode, derived.cpaResult());
        buildPredictedCpaNode(derivedNode, derived.predictedCpaResult());
        buildEncounterNode(derivedNode, derived.encounterResult());
        buildRiskAssessmentNode(derivedNode, derived.riskAssessment());
        buildPredictionNode(derivedNode, derived.predictionResult());
    }

    private void buildCpaNode(ObjectNode parent, CpaTcpaResult cpa) {
        if (cpa == null) { parent.putNull("cpa"); return; }
        parent.putObject("cpa")
                .put("cpa_distance_nm", GeoUtils.metersToNm(cpa.getCpaDistance()))
                .put("tcpa_sec", cpa.getTcpaTime())
                .put("approaching", cpa.isApproaching())
                .put("cpa_valid", cpa.isCpaValid());
    }

    private void buildPredictedCpaNode(ObjectNode parent, PredictedCpaTcpaResult predictedCpa) {
        if (predictedCpa == null) { parent.putNull("predicted_cpa"); return; }
        parent.putObject("predicted_cpa")
                .put("cpa_distance_nm", GeoUtils.metersToNm(predictedCpa.getCpaDistanceMeters()))
                .put("tcpa_sec", predictedCpa.getTcpaSeconds())
                .put("approaching", predictedCpa.isApproaching());
    }

    private void buildEncounterNode(ObjectNode parent, EncounterClassificationResult encounter) {
        if (encounter == null) { parent.putNull("encounter"); return; }
        ObjectNode node = parent.putObject("encounter");
        if (encounter.getEncounterType() != null) node.put("encounter_type", encounter.getEncounterType().name());
        else node.putNull("encounter_type");
        node.put("relative_bearing_deg", encounter.getRelativeBearingDeg())
                .put("course_difference_deg", encounter.getCourseDifferenceDeg());
    }

    private void buildRiskAssessmentNode(ObjectNode parent, TargetRiskAssessment risk) {
        if (risk == null) { parent.putNull("risk_assessment"); return; }
        ObjectNode node = parent.putObject("risk_assessment")
                .put("risk_level", risk.getRiskLevel())
                .put("risk_score", risk.getRiskScore())
                .put("risk_confidence", risk.getRiskConfidence())
                .put("explanation_source", risk.getExplanationSource())
                .put("explanation_text", risk.getExplanationText());
        if (risk.getDomainPenetration() != null) node.put("domain_penetration", risk.getDomainPenetration());
        else node.putNull("domain_penetration");
    }

    private void buildPredictionNode(ObjectNode parent, CvPredictionResult prediction) {
        if (prediction == null) { parent.putNull("prediction"); return; }
        ObjectNode node = parent.putObject("prediction");
        if (prediction.getPredictionTime() != null) node.put("prediction_time", prediction.getPredictionTime().toString());
        else node.putNull("prediction_time");
        node.put("horizon_seconds", prediction.getHorizonSeconds());
        ArrayNode traj = node.putArray("trajectory");
        if (prediction.getTrajectory() != null) {
            for (CvPredictionResult.PredictedPoint pt : prediction.getTrajectory()) {
                traj.addObject()
                        .put("offset_seconds", pt.getOffsetSeconds())
                        .put("longitude", pt.getLongitude())
                        .put("latitude", pt.getLatitude());
            }
        }
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }
}
