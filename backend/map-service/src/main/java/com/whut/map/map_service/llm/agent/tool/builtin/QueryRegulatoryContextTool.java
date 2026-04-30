package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.graph.GraphQueryPort;
import com.whut.map.map_service.llm.agent.graph.ManeuverAction;
import com.whut.map.map_service.llm.agent.graph.RegulatoryContext;
import com.whut.map.map_service.llm.agent.graph.RegulatoryQuery;
import com.whut.map.map_service.llm.agent.graph.Rule;
import com.whut.map.map_service.llm.agent.graph.VisibilityCondition;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "llm.graph", name = "enabled", havingValue = "true")
public class QueryRegulatoryContextTool implements AgentTool {

    private final ObjectMapper mapper;
    private final GraphQueryPort graphQueryPort;
    private final ToolDefinition definition;

    public QueryRegulatoryContextTool(ObjectMapper mapper, GraphQueryPort graphQueryPort) {
        this.mapper = mapper;
        this.graphQueryPort = graphQueryPort;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("target_id")
                .put("type", "string")
                .put("description", "Optional. Target ship ID to derive encounter_type and own_ship_role from snapshot.");
        properties.putObject("encounter_type")
                .put("type", "string")
                .put("description", "Optional. Overrides snapshot-derived encounter type. Enum: HEAD_ON, OVERTAKING, CROSSING, UNDEFINED.");
        properties.putObject("own_ship_role")
                .put("type", "string")
                .put("description", "Optional. Overrides snapshot-derived own-ship role. Enum: GIVE_WAY, STAND_ON, MUTUAL_ACTION, UNKNOWN, NOT_APPLICABLE.");
        properties.putObject("visibility_condition")
                .put("type", "string")
                .put("description", "Optional. Default OPEN_VISIBILITY. Enum: OPEN_VISIBILITY, RESTRICTED_VISIBILITY, UNKNOWN.");
        schema.putArray("required");

        this.definition = new ToolDefinition(
                AgentToolNames.QUERY_REGULATORY_CONTEXT,
                "Query COLREGS Part B regulatory context for the current encounter. Returns applicable rules, recommended maneuver actions, and limitations. Read encounter_type / own_ship_role from the snapshot if not provided.",
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
        String visibilityStr = stringArg(call, "visibility_condition");

        EncounterType encounterType = null;
        OwnShipRole ownShipRole = null;
        List<String> assumptions = new ArrayList<>();

        if (targetId != null) {
            TargetDerivedSnapshot derived = snapshot.targetDetails() != null
                    ? snapshot.targetDetails().get(targetId) : null;
            if (derived == null) {
                return errorResult(call, "TARGET_NOT_FOUND",
                        "Target " + targetId + " not found in snapshot_version " + snapshot.snapshotVersion());
            }
            EncounterClassificationResult enc = derived.encounterResult();
            if (enc != null) {
                encounterType = enc.getEncounterType();
                ownShipRole = enc.getOwnShipRole();
            }
        }

        if (explicitEncounterType != null) {
            EncounterType parsed = parseEnum(EncounterType.class, explicitEncounterType);
            if (parsed == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Unknown encounter_type '" + explicitEncounterType + "'");
            }
            encounterType = parsed;
            assumptions.add("override:encounter_type");
        }

        if (explicitOwnShipRole != null) {
            OwnShipRole parsed = parseEnum(OwnShipRole.class, explicitOwnShipRole);
            if (parsed == null) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "Unknown own_ship_role '" + explicitOwnShipRole + "'");
            }
            ownShipRole = parsed;
            assumptions.add("override:own_ship_role");
        }

        if (encounterType == null && ownShipRole == null) {
            return errorResult(call, "INVALID_ARGUMENT",
                    "either target_id or (encounter_type, own_ship_role) must be provided");
        }

        VisibilityCondition visibility = VisibilityCondition.OPEN_VISIBILITY;
        if (visibilityStr != null) {
            VisibilityCondition parsed = parseEnum(VisibilityCondition.class, visibilityStr);
            if (parsed != null) {
                visibility = parsed;
            }
        }

        if (encounterType == EncounterType.OVERTAKING) {
            assumptions.add("overtaking_role_inferred_from_geometry_only_converging_speed_not_verified");
        }

        RegulatoryContext context = graphQueryPort.findRegulatoryContext(
                new RegulatoryQuery(encounterType, ownShipRole, null, visibility));

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());
        if (encounterType != null) payload.put("encounter_type", encounterType.name());
        else payload.putNull("encounter_type");
        if (ownShipRole != null) payload.put("own_ship_role", ownShipRole.name());
        else payload.putNull("own_ship_role");
        payload.put("visibility_condition", visibility.name());

        buildRulesArray(payload, context.rules());
        buildActionsArray(payload, context.recommendedActions());
        buildStringArray(payload, "limitations", context.limitations());
        buildStringArray(payload, "assumptions", assumptions);

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private void buildRulesArray(ObjectNode payload, List<Rule> rules) {
        ArrayNode arr = payload.putArray("rules");
        for (Rule rule : rules) {
            ObjectNode node = arr.addObject()
                    .put("rule_id", rule.ruleId())
                    .put("rule_number", rule.ruleNumber())
                    .put("title", rule.title())
                    .put("source_citation", rule.sourceCitation());
            if (rule.summaryEn() != null) node.put("summary_en", rule.summaryEn());
            if (rule.summaryZh() != null) node.put("summary_zh", rule.summaryZh());
            if (rule.principle() != null) node.put("principle", rule.principle());
            ArrayNode lim = node.putArray("limitations");
            if (rule.limitations() != null) rule.limitations().forEach(lim::add);
        }
    }

    private void buildActionsArray(ObjectNode payload, List<ManeuverAction> actions) {
        ArrayNode arr = payload.putArray("recommended_actions");
        for (ManeuverAction action : actions) {
            ObjectNode node = arr.addObject()
                    .put("action_id", action.actionId())
                    .put("type", action.type().name())
                    .put("description_en", action.descriptionEn())
                    .put("description_zh", action.descriptionZh());
            if (action.rationale() != null) node.put("rationale", action.rationale());
        }
    }

    private void buildStringArray(ObjectNode payload, String field, List<String> items) {
        ArrayNode arr = payload.putArray(field);
        if (items != null) items.forEach(arr::add);
    }

    private String stringArg(ToolCall call, String key) {
        if (!call.arguments().has(key) || call.arguments().get(key).isNull()) return null;
        String val = call.arguments().get(key).asText(null);
        return (val == null || val.isBlank()) ? null : val;
    }

    private <E extends Enum<E>> E parseEnum(Class<E> enumClass, String value) {
        try {
            return Enum.valueOf(enumClass, value.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
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
