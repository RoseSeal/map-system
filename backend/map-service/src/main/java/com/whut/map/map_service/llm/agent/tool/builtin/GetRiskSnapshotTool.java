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
import com.whut.map.map_service.llm.agent.tool.AgentToolRegistry;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class GetRiskSnapshotTool implements AgentTool {

    private final ObjectMapper mapper;
    private final ToolDefinition definition;

    public GetRiskSnapshotTool(ObjectMapper mapper) {
        this.mapper = mapper;
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties");
        this.definition = new ToolDefinition(
                AgentToolNames.GET_RISK_SNAPSHOT,
                "Returns a concise summary of the current risk scene snapshot: own-ship position, highest risk level, target counts by risk level, and top 3 riskiest target IDs.",
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

        LlmRiskOwnShipContext ownShip = riskContext.getOwnShip();
        if (ownShip == null) {
            return errorResult(call, "SNAPSHOT_INCOMPLETE", "Own-ship data is not available in this snapshot");
        }
        List<LlmRiskTargetContext> targets = riskContext.getTargets();
        if (targets == null) {
            targets = Collections.emptyList();
        }

        Map<RiskLevel, Integer> levelCounts = new EnumMap<>(RiskLevel.class);
        for (RiskLevel lvl : RiskLevel.values()) {
            levelCounts.put(lvl, 0);
        }
        int approachingCount = 0;
        for (LlmRiskTargetContext t : targets) {
            if (t.getRiskLevel() != null) {
                levelCounts.merge(t.getRiskLevel(), 1, Integer::sum);
            }
            if (t.isApproaching()) {
                approachingCount++;
            }
        }

        RiskLevel highest = RiskLevel.SAFE;
        if (levelCounts.get(RiskLevel.ALARM) > 0) highest = RiskLevel.ALARM;
        else if (levelCounts.get(RiskLevel.WARNING) > 0) highest = RiskLevel.WARNING;
        else if (levelCounts.get(RiskLevel.CAUTION) > 0) highest = RiskLevel.CAUTION;

        List<String> topIds = targets.stream()
                .sorted(AgentToolRegistry.TARGET_RISK_COMPARATOR)
                .limit(3)
                .map(LlmRiskTargetContext::getTargetId)
                .collect(Collectors.toList());

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion())
                .put("highest_risk_level", highest.name())
                .put("target_count", targets.size())
                .put("approaching_target_count", approachingCount);

        if (ownShip != null) {
            ObjectNode ownShipNode = payload.putObject("own_ship")
                    .put("id", ownShip.getId())
                    .put("longitude", ownShip.getLongitude())
                    .put("latitude", ownShip.getLatitude())
                    .put("sog_kn", ownShip.getSog())
                    .put("cog_deg", ownShip.getCog());
            if (ownShip.getHeading() != null) {
                ownShipNode.put("heading_deg", ownShip.getHeading());
            } else {
                ownShipNode.putNull("heading_deg");
            }
        } else {
            payload.putNull("own_ship");
        }

        ObjectNode countsNode = payload.putObject("risk_level_counts");
        for (RiskLevel lvl : RiskLevel.values()) {
            countsNode.put(lvl.name(), levelCounts.get(lvl));
        }

        ArrayNode topIdsNode = payload.putArray("top_risk_target_ids");
        topIds.forEach(topIdsNode::add);

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
