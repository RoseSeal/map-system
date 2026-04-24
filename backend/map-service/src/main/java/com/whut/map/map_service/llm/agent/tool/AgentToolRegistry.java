package com.whut.map.map_service.llm.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class AgentToolRegistry {

    /**
     * Canonical target comparator shared across all tools:
     * 1. risk_level desc  2. risk_score desc (null = min)  3. approaching first
     * 4. tcpa asc within approaching bucket (tcpaSec <= 0 last)  5. distance asc  6. id asc
     */
    public static final Comparator<LlmRiskTargetContext> TARGET_RISK_COMPARATOR = (a, b) -> {
        int levelA = a.getRiskLevel() == null ? -1 : a.getRiskLevel().ordinal();
        int levelB = b.getRiskLevel() == null ? -1 : b.getRiskLevel().ordinal();
        int cmp = Integer.compare(levelB, levelA);
        if (cmp != 0) return cmp;

        double scoreA = a.getRiskScore() == null ? Double.NEGATIVE_INFINITY : a.getRiskScore();
        double scoreB = b.getRiskScore() == null ? Double.NEGATIVE_INFINITY : b.getRiskScore();
        cmp = Double.compare(scoreB, scoreA);
        if (cmp != 0) return cmp;

        cmp = Boolean.compare(b.isApproaching(), a.isApproaching());
        if (cmp != 0) return cmp;

        boolean aValidTcpa = a.getTcpaSec() > 0;
        boolean bValidTcpa = b.getTcpaSec() > 0;
        if (aValidTcpa != bValidTcpa) return aValidTcpa ? -1 : 1;
        if (aValidTcpa) {
            cmp = Double.compare(a.getTcpaSec(), b.getTcpaSec());
            if (cmp != 0) return cmp;
        }

        double distA = a.getCurrentDistanceNm() == null ? Double.MAX_VALUE : a.getCurrentDistanceNm();
        double distB = b.getCurrentDistanceNm() == null ? Double.MAX_VALUE : b.getCurrentDistanceNm();
        cmp = Double.compare(distA, distB);
        if (cmp != 0) return cmp;

        return a.getTargetId().compareTo(b.getTargetId());
    };

    private final Map<String, AgentTool> tools;
    private final List<ToolDefinition> sortedDefinitions;
    private final ObjectMapper mapper;

    public AgentToolRegistry(List<AgentTool> agentTools, ObjectMapper mapper) {
        Map<String, AgentTool> toolMap = new LinkedHashMap<>();
        for (AgentTool tool : agentTools) {
            String name = tool.getDefinition().name();
            if (toolMap.containsKey(name)) {
                throw new IllegalStateException("Duplicate agent tool name: " + name);
            }
            toolMap.put(name, tool);
        }
        this.tools = Map.copyOf(toolMap);
        this.sortedDefinitions = toolMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getValue().getDefinition())
                .collect(Collectors.toUnmodifiableList());
        this.mapper = mapper;
    }

    public List<ToolDefinition> getToolDefinitions() {
        return sortedDefinitions;
    }

    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        AgentTool tool = tools.get(call.toolName());
        if (tool == null) {
            ObjectNode payload = mapper.createObjectNode()
                    .put("status", "ERROR")
                    .put("error_code", "UNKNOWN_TOOL")
                    .put("message", "Unknown tool: " + call.toolName());
            return new ToolResult(call.callId(), call.toolName(), payload);
        }
        return tool.execute(call, snapshot);
    }
}
