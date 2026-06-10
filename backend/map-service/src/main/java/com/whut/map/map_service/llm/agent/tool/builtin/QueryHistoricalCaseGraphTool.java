package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.casegraph.CaseGraphUnavailableException;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCase;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseQuery;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseQueryPort;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseResult;
import com.whut.map.map_service.llm.agent.graph.VisibilityCondition;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
@ConditionalOnProperty(prefix = "llm.case-graph", name = "enabled", havingValue = "true")
@Slf4j
public class QueryHistoricalCaseGraphTool implements AgentTool {

    private static final String LOW_VISIBILITY = "LOW_VISIBILITY";
    private static final Set<String> WATER_AREAS = Set.of("开阔水域", "限制水域", "狭水道");

    private final ObjectMapper mapper;
    private final HistoricalCaseQueryPort caseQueryPort;
    private final ToolDefinition definition;

    public QueryHistoricalCaseGraphTool(ObjectMapper mapper, HistoricalCaseQueryPort caseQueryPort) {
        this.mapper = mapper;
        this.caseQueryPort = caseQueryPort;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("target_id")
                .put("type", "string")
                .put("description", "Optional. Target ID used to derive encounter type, own-ship role, and risk level.");
        properties.putObject("encounter_type")
                .put("type", "string")
                .put("description", "Optional override. Enum: HEAD_ON, OVERTAKING, CROSSING, UNDEFINED.");
        properties.putObject("own_ship_role")
                .put("type", "string")
                .put("description", "Optional override. Enum: GIVE_WAY, STAND_ON, MUTUAL_ACTION, UNKNOWN, NOT_APPLICABLE.");
        properties.putObject("visibility_condition")
                .put("type", "string")
                .put("description", "Optional override. Enum: OPEN_VISIBILITY, RESTRICTED_VISIBILITY, UNKNOWN.");
        properties.putObject("water_area")
                .put("type", "string")
                .put("description", "Optional controlled value: 开阔水域, 限制水域, 狭水道.");
        properties.putObject("query_text")
                .put("type", "string")
                .put("description", "Optional natural-language retrieval context, primarily for chat agent queries.");
        schema.putArray("required");

        this.definition = new ToolDefinition(
                AgentToolNames.QUERY_HISTORICAL_CASE_GRAPH,
                "Retrieve similar historical maritime cases from the frozen snapshot. Returns traceable case facts for advisory evidence.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        String targetId = stringArg(call, "target_id");
        String explicitEncounterType = stringArg(call, "encounter_type");
        String explicitOwnShipRole = stringArg(call, "own_ship_role");
        String explicitVisibility = stringArg(call, "visibility_condition");
        String waterArea = stringArg(call, "water_area");
        String queryText = stringArg(call, "query_text");
        if (waterArea != null && !WATER_AREAS.contains(waterArea)) {
            return errorResult(call, "INVALID_ARGUMENT", "Unknown water_area '" + waterArea + "'");
        }

        EncounterType encounterType = null;
        OwnShipRole ownShipRole = null;
        RiskLevel riskLevel = null;

        if (targetId != null) {
            TargetDerivedSnapshot derived = snapshot.targetDetails() == null
                    ? null : snapshot.targetDetails().get(targetId);
            if (derived == null) {
                return errorResult(call, "TARGET_NOT_FOUND",
                        "Target " + targetId + " not found in snapshot_version " + snapshot.snapshotVersion());
            }
            EncounterClassificationResult encounter = derived.encounterResult();
            if (encounter != null) {
                encounterType = encounter.getEncounterType();
                ownShipRole = encounter.getOwnShipRole();
            }
            riskLevel = targetRiskLevel(snapshot, targetId);
        }

        if (explicitEncounterType != null) {
            encounterType = parseEnum(EncounterType.class, explicitEncounterType);
            if (encounterType == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Unknown encounter_type '" + explicitEncounterType + "'");
            }
        }

        if (explicitOwnShipRole != null) {
            ownShipRole = parseEnum(OwnShipRole.class, explicitOwnShipRole);
            if (ownShipRole == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Unknown own_ship_role '" + explicitOwnShipRole + "'");
            }
        }

        VisibilityCondition visibility = deriveVisibility(snapshot);
        if (explicitVisibility != null) {
            visibility = parseEnum(VisibilityCondition.class, explicitVisibility);
            if (visibility == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Unknown visibility_condition '" + explicitVisibility + "'");
            }
        }

        if (encounterType == null && ownShipRole == null && queryText == null) {
            return errorResult(call, "INVALID_ARGUMENT",
                    "either target_id, encounter_type/own_ship_role or query_text must be provided");
        }

        if (riskLevel == null) {
            riskLevel = highestRiskLevel(snapshot);
        }
        int targetCount = targetCount(snapshot);

        HistoricalCaseQuery query = new HistoricalCaseQuery(
                encounterType,
                ownShipRole,
                visibility,
                riskLevel,
                waterArea,
                null,
                null,
                targetCount,
                queryText,
                null,
                null
        );

        HistoricalCaseResult result;
        try {
            result = caseQueryPort.findSimilarCases(query);
        } catch (CaseGraphUnavailableException e) {
            return errorResult(
                    call,
                    "CASE_GRAPH_UNAVAILABLE",
                    "historical case retrieval unavailable; continue advisory without case evidence"
            );
        }
        log.debug(
                "Historical case retrieval completed: mode={}, cases={}, metrics={}",
                result == null ? null : result.mode(),
                result == null || result.cases() == null ? 0 : result.cases().size(),
                result == null ? null : result.metrics()
        );

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());
        ObjectNode queryNode = payload.putObject("query");
        putEnum(queryNode, "encounter_type", encounterType);
        putEnum(queryNode, "own_ship_role", ownShipRole);
        putEnum(queryNode, "visibility_condition", visibility);
        putEnum(queryNode, "risk_level", riskLevel);
        putNullable(queryNode, "water_area", waterArea);
        putNullable(queryNode, "query_text", queryText);

        ArrayNode casesNode = payload.putArray("cases");
        List<HistoricalCase> cases = result == null || result.cases() == null
                ? List.of() : result.cases();
        cases.forEach(caseItem -> appendCase(casesNode, caseItem));
        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private void appendCase(ArrayNode casesNode, HistoricalCase caseItem) {
        ObjectNode node = casesNode.addObject();
        putNullable(node, "case_id", caseItem.caseId());
        putNullable(node, "title", caseItem.title());
        node.put("relevance", caseItem.relevance());
        putNullable(node, "water_area", caseItem.waterArea());
        putNullable(node, "visibility", caseItem.visibility());
        putNullable(node, "own_ship_role", caseItem.ownShipRole());
        putNullable(node, "encounter_type", caseItem.encounterType());
        putNullable(node, "risk_level", caseItem.riskLevel());
        putNullable(node, "target_summary", caseItem.targetSummary());
        ArrayNode rules = node.putArray("colregs_rules");
        if (caseItem.colregsRules() != null) {
            caseItem.colregsRules().forEach(rules::add);
        }
        putNullable(node, "outcome", caseItem.outcome());
        putNullable(node, "action_digest", caseItem.actionDigest());
        putNullable(node, "lesson", caseItem.lesson());
    }

    private VisibilityCondition deriveVisibility(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null) {
            return VisibilityCondition.UNKNOWN;
        }
        LlmRiskWeatherContext weather = snapshot.riskContext().getWeather();
        if (weather == null || weather.getVisibilityNm() == null) {
            return VisibilityCondition.UNKNOWN;
        }
        List<String> alerts = weather.getActiveAlerts();
        boolean restricted = alerts != null && alerts.stream()
                .filter(Objects::nonNull)
                .anyMatch(LOW_VISIBILITY::equalsIgnoreCase);
        return restricted ? VisibilityCondition.RESTRICTED_VISIBILITY : VisibilityCondition.OPEN_VISIBILITY;
    }

    private RiskLevel targetRiskLevel(AgentSnapshot snapshot, String targetId) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return null;
        }
        return snapshot.riskContext().getTargets().stream()
                .filter(Objects::nonNull)
                .filter(target -> Objects.equals(targetId, target.getTargetId()))
                .map(LlmRiskTargetContext::getRiskLevel)
                .findFirst()
                .orElse(null);
    }

    private RiskLevel highestRiskLevel(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return null;
        }
        return snapshot.riskContext().getTargets().stream()
                .filter(Objects::nonNull)
                .map(LlmRiskTargetContext::getRiskLevel)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private int targetCount(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return 0;
        }
        return snapshot.riskContext().getTargets().size();
    }

    private String stringArg(ToolCall call, String key) {
        if (!call.arguments().has(key) || call.arguments().get(key).isNull()) {
            return null;
        }
        String value = call.arguments().get(key).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        try {
            return Enum.valueOf(enumClass, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void putEnum(ObjectNode node, String key, Enum<?> value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value.name());
        }
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value == null) {
            node.putNull(key);
        } else {
            node.put(key, value);
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
