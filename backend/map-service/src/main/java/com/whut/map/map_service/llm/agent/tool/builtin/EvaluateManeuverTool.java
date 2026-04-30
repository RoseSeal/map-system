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
import com.whut.map.map_service.risk.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.springframework.stereotype.Component;

@Component
public class EvaluateManeuverTool implements AgentTool {

    private final ObjectMapper mapper;
    private final CpaTcpaBatchCalculator cpaTcpaCalculator;
    private final ToolDefinition definition;

    public EvaluateManeuverTool(ObjectMapper mapper, CpaTcpaBatchCalculator cpaTcpaCalculator) {
        this.mapper = mapper;
        this.cpaTcpaCalculator = cpaTcpaCalculator;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("target_id")
                .put("type", "string")
                .put("description", "Required. Target ship identifier to evaluate maneuver against.");
        properties.putObject("maneuver_type")
                .put("type", "string")
                .put("description", "Required. Type of maneuver. Enum: COURSE_CHANGE, SPEED_CHANGE.");
        properties.putObject("magnitude")
                .put("type", "number")
                .put("description", "Required. For COURSE_CHANGE: degrees, positive=starboard, negative=port, abs<=180. For SPEED_CHANGE: knots, negative=decelerate; result sog must be >= 0.");
        properties.putObject("apply_seconds")
                .put("type", "number")
                .put("description", "Optional. Must be 0 or omitted in v1.0.");
        schema.putArray("required").add("target_id").add("maneuver_type").add("magnitude");

        this.definition = new ToolDefinition(
                AgentToolNames.EVALUATE_MANEUVER,
                "Evaluate the immediate effect of an own-ship maneuver on a single target. Course change positive degrees mean turn to starboard; speed change in knots, negative means slowdown. Returns before/after CPA values from CpaTcpaBatchCalculator. Assumes instantaneous execution; turning dynamics are not modeled.",
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
        if (targetId == null) {
            return errorResult(call, "INVALID_ARGUMENT", "Required field 'target_id' is missing");
        }

        String maneuverTypeStr = stringArg(call, "maneuver_type");
        if (maneuverTypeStr == null) {
            return errorResult(call, "INVALID_ARGUMENT", "Required field 'maneuver_type' is missing");
        }
        if (!maneuverTypeStr.equals("COURSE_CHANGE") && !maneuverTypeStr.equals("SPEED_CHANGE")) {
            return errorResult(call, "INVALID_ARGUMENT",
                    "Unknown maneuver_type '" + maneuverTypeStr + "'; must be COURSE_CHANGE or SPEED_CHANGE");
        }

        JsonNode magnitudeNode = call.arguments().get("magnitude");
        if (magnitudeNode == null || magnitudeNode.isNull()) {
            return errorResult(call, "INVALID_ARGUMENT", "Required field 'magnitude' is missing");
        }
        if (!magnitudeNode.isNumber()) {
            return errorResult(call, "INVALID_ARGUMENT", "Field 'magnitude' must be a number");
        }
        double magnitude = magnitudeNode.doubleValue();

        JsonNode applySecondsNode = call.arguments().get("apply_seconds");
        if (applySecondsNode != null && !applySecondsNode.isNull()) {
            if (!applySecondsNode.isNumber() || applySecondsNode.doubleValue() != 0) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "apply_seconds is reserved for future use; must be 0 or omitted in v1.0");
            }
        }

        ShipStatus ownShip = snapshot.frozenOwnShip();
        if (ownShip == null) {
            return errorResult(call, "OWN_SHIP_UNAVAILABLE",
                    "Frozen own-ship state is not available in this snapshot");
        }

        ShipStatus targetShip = snapshot.frozenTargetShips() != null
                ? snapshot.frozenTargetShips().get(targetId) : null;
        if (targetShip == null) {
            return errorResult(call, "TARGET_NOT_FOUND",
                    "Target " + targetId + " not found in snapshot_version " + snapshot.snapshotVersion());
        }

        TargetDerivedSnapshot derived = snapshot.targetDetails() != null
                ? snapshot.targetDetails().get(targetId) : null;

        if ("COURSE_CHANGE".equals(maneuverTypeStr)) {
            if (Math.abs(magnitude) > 180) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "COURSE_CHANGE magnitude absolute value must not exceed 180 degrees");
            }
        } else {
            double sogAfter = ownShip.getSog() + magnitude;
            if (sogAfter < 0) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "SPEED_CHANGE magnitude would result in negative sog (" + sogAfter + " kn)");
            }
        }

        CpaTcpaResult before = derived != null ? derived.cpaResult() : null;

        ShipStatus simulated = applyManeuver(ownShip, maneuverTypeStr, magnitude);
        CpaTcpaResult after = cpaTcpaCalculator.calculateOne(simulated, targetShip);
        if (after == null) {
            return errorResult(call, "OWN_SHIP_UNAVAILABLE",
                    "CPA calculation returned null; target may be same as own ship");
        }

        double beforeDcpaNm = before != null ? GeoUtils.metersToNm(before.getCpaDistance()) : 0.0;
        double afterDcpaNm = GeoUtils.metersToNm(after.getCpaDistance());
        double deltaDcpaNm = afterDcpaNm - beforeDcpaNm;

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion())
                .put("target_id", targetId);

        ObjectNode maneuverNode = payload.putObject("maneuver")
                .put("type", maneuverTypeStr)
                .put("magnitude", magnitude)
                .put("magnitude_unit", "COURSE_CHANGE".equals(maneuverTypeStr) ? "deg" : "kn")
                .put("apply_seconds", 0);

        buildCpaNode(payload.putObject("before"), before);
        buildCpaNode(payload.putObject("after"), after);
        payload.put("delta_dcpa_nm", deltaDcpaNm);

        ArrayNode assumptions = payload.putArray("assumptions");
        assumptions.add("instantaneous_execution");
        assumptions.add("no_turning_dynamics");
        assumptions.add("target_state_unchanged");

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private ShipStatus applyManeuver(ShipStatus ownShip, String maneuverType, double magnitude) {
        ShipStatus.ShipStatusBuilder builder = ShipStatus.builder()
                .id(ownShip.getId())
                .role(ownShip.getRole())
                .longitude(ownShip.getLongitude())
                .latitude(ownShip.getLatitude())
                .sog(ownShip.getSog())
                .cog(ownShip.getCog())
                .heading(ownShip.getHeading())
                .msgTime(ownShip.getMsgTime())
                .confidence(ownShip.getConfidence())
                .qualityFlags(ownShip.getQualityFlags());

        if ("COURSE_CHANGE".equals(maneuverType)) {
            double cogAfter = ((ownShip.getCog() + magnitude) % 360 + 360) % 360;
            builder.cog(cogAfter);
        } else {
            builder.sog(ownShip.getSog() + magnitude);
        }
        return builder.build();
    }

    private void buildCpaNode(ObjectNode node, CpaTcpaResult cpa) {
        if (cpa == null) {
            node.put("dcpa_nm", 0.0)
                    .put("tcpa_sec", 0.0)
                    .put("is_approaching", false)
                    .put("cpa_valid", false);
        } else {
            node.put("dcpa_nm", GeoUtils.metersToNm(cpa.getCpaDistance()))
                    .put("tcpa_sec", cpa.getTcpaTime())
                    .put("is_approaching", cpa.isApproaching())
                    .put("cpa_valid", cpa.isCpaValid());
        }
    }

    private String stringArg(ToolCall call, String key) {
        if (!call.arguments().has(key) || call.arguments().get(key).isNull()) return null;
        String val = call.arguments().get(key).asText(null);
        return (val == null || val.isBlank()) ? null : val;
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }
}
