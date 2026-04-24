package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.llm.agent.tool.AgentToolRegistry;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GetTopRiskTargetsTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 3;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 10;

    private final ObjectMapper mapper;
    private final ToolDefinition definition;

    public GetTopRiskTargetsTool(ObjectMapper mapper) {
        this.mapper = mapper;
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("limit")
                .put("type", "integer")
                .put("description", "Maximum number of targets to return (1-10, default 3)")
                .put("minimum", MIN_LIMIT)
                .put("maximum", MAX_LIMIT);
        ObjectNode minLevelProp = properties.putObject("min_level");
        minLevelProp.put("type", "string").put("description", "Minimum risk level filter");
        ArrayNode enumValues = minLevelProp.putArray("enum");
        for (RiskLevel lvl : RiskLevel.values()) {
            enumValues.add(lvl.name());
        }
        this.definition = new ToolDefinition(
                AgentToolNames.GET_TOP_RISK_TARGETS,
                "Returns the top risk targets sorted by risk level, score, and CPA data. Optionally filtered by minimum risk level and limited in count.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        LlmRiskContext riskContext = snapshot.riskContext();
        if (riskContext == null) {
            return errorResult(call, "SNAPSHOT_INCOMPLETE", "Risk context is not available in this snapshot");
        }

        int limit = DEFAULT_LIMIT;
        if (call.arguments().has("limit")) {
            JsonNode limitNode = call.arguments().get("limit");
            if (limitNode.isNull() || !limitNode.isIntegralNumber()) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Field 'limit' must be an integer between " + MIN_LIMIT + " and " + MAX_LIMIT);
            }
            limit = limitNode.asInt();
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Field 'limit' must be between " + MIN_LIMIT + " and " + MAX_LIMIT);
            }
        }

        RiskLevel minLevel = null;
        if (call.arguments().has("min_level")) {
            String minLevelStr = call.arguments().get("min_level").asText();
            minLevel = RiskLevel.fromValue(minLevelStr);
            if (minLevel == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Field 'min_level' must be one of: SAFE, CAUTION, WARNING, ALARM");
            }
        }

        List<LlmRiskTargetContext> targets = riskContext.getTargets();
        if (targets == null) {
            targets = Collections.emptyList();
        }

        final RiskLevel effectiveMinLevel = minLevel;
        List<LlmRiskTargetContext> filtered = targets.stream()
                .filter(t -> effectiveMinLevel == null
                        || (t.getRiskLevel() != null && t.getRiskLevel().ordinal() >= effectiveMinLevel.ordinal()))
                .sorted(AgentToolRegistry.TARGET_RISK_COMPARATOR)
                .limit(limit)
                .collect(Collectors.toList());

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());

        ArrayNode items = payload.putArray("items");
        for (LlmRiskTargetContext t : filtered) {
            ObjectNode item = items.addObject()
                    .put("target_id", t.getTargetId())
                    .put("approaching", t.isApproaching())
                    .put("dcpa_nm", t.getDcpaNm())
                    .put("tcpa_sec", t.getTcpaSec());
            if (t.getRiskLevel() != null) item.put("risk_level", t.getRiskLevel().name());
            else item.putNull("risk_level");
            if (t.getRiskScore() != null) item.put("risk_score", t.getRiskScore());
            else item.putNull("risk_score");
            if (t.getCurrentDistanceNm() != null) item.put("current_distance_nm", t.getCurrentDistanceNm());
            else item.putNull("current_distance_nm");
            if (t.getEncounterType() != null) item.put("encounter_type", t.getEncounterType().name());
            else item.putNull("encounter_type");
        }

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }
}
