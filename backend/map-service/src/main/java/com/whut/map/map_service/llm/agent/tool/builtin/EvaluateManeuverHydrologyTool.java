package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.chart.dto.GeoPoint;
import com.whut.map.map_service.chart.dto.HydrologyRouteAssessment;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EvaluateManeuverHydrologyTool implements AgentTool {

    private static final int MAX_SAMPLE_COUNT = 24;
    private static final double DEFAULT_SEARCH_RADIUS_NM = 1.0;

    private final ObjectMapper mapper;
    private final HydrologyContextService hydrologyContextService;
    private final SafetyContourStateHolder safetyContourStateHolder;
    private final ToolDefinition definition;

    public EvaluateManeuverHydrologyTool(
            ObjectMapper mapper,
            HydrologyContextService hydrologyContextService,
            SafetyContourStateHolder safetyContourStateHolder
    ) {
        this.mapper = mapper;
        this.hydrologyContextService = hydrologyContextService;
        this.safetyContourStateHolder = safetyContourStateHolder;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("course_change_deg")
                .put("type", "number")
                .put("description", "Required. Proposed course change in degrees; positive = starboard, negative = port, abs<=180.");
        properties.putObject("lookahead_min")
                .put("type", "number")
                .put("description", "Required. Lookahead horizon in minutes, range (0, 30].");
        schema.putArray("required").add("course_change_deg").add("lookahead_min");

        this.definition = new ToolDefinition(
                AgentToolNames.EVALUATE_MANEUVER_HYDROLOGY,
                "Evaluate hydrology feasibility for an assumed own-ship course change. Samples the route for depth, shoal and obstruction facts only; does not compute CPA/TCPA. Sampling is discrete: horizons beyond 12 minutes use the 24-sample cap, so sample spacing increases with lookahead and narrow hazards may be missed.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        Double courseChangeDeg = numberArg(call, "course_change_deg");
        Double lookaheadMin = numberArg(call, "lookahead_min");
        if (courseChangeDeg == null || lookaheadMin == null) {
            return errorResult(call, "INVALID_ARGUMENT", "course_change_deg and lookahead_min are required numeric fields");
        }
        if (Math.abs(courseChangeDeg) > 180.0) {
            return errorResult(call, "INVALID_ARGUMENT", "course_change_deg absolute value must not exceed 180");
        }
        if (lookaheadMin <= 0.0 || lookaheadMin > 30.0) {
            return errorResult(call, "INVALID_ARGUMENT", "lookahead_min must be in range (0, 30]");
        }

        ShipStatus ownShip = snapshot.frozenOwnShip();
        String unavailable = validateOwnShip(ownShip);
        if (unavailable != null) {
            return errorResult(call, "OWN_SHIP_UNAVAILABLE", unavailable);
        }

        double assumedCogDeg = normalize360(ownShip.getCog() + courseChangeDeg);
        List<GeoPoint> sampledPoints = sampleRoute(ownShip, assumedCogDeg, lookaheadMin);
        double safetyContourMeters = safetyContourStateHolder.getCurrentDepthMeters();
        HydrologyRouteAssessment routeAssessment = hydrologyContextService.evaluateRoute(
                sampledPoints,
                safetyContourMeters,
                DEFAULT_SEARCH_RADIUS_NM
        );

        boolean hasHydrologyData = routeAssessment.resolvedSampleCount() > 0;
        ObjectNode payload = mapper.createObjectNode()
                .put("status", hasHydrologyData ? "OK" : "NO_HYDROLOGY_DATA")
                .put("source", "hydrology")
                .put("snapshot_version", snapshot.snapshotVersion());
        if (!hasHydrologyData) {
            payload.put("message", "Hydrology data unavailable for sampled maneuver route");
        }
        payload.putObject("maneuver")
                .put("course_change_deg", courseChangeDeg)
                .put("lookahead_min", lookaheadMin)
                .put("assumed_cog_deg", assumedCogDeg);

        ObjectNode routeNode = payload.putObject("route_hydrology")
                .put("sample_count", routeAssessment.sampleCount())
                .put("resolved_sample_count", routeAssessment.resolvedSampleCount())
                .put("data_complete", routeAssessment.dataComplete());
        if (hasHydrologyData) {
            routeNode.put("crosses_shoal", routeAssessment.crossesShoal());
        } else {
            routeNode.putNull("crosses_shoal");
        }
        putNullable(routeNode, "min_depth_m", routeAssessment.minDepthM());
        putNullable(routeNode, "nearest_shoal_nm", routeAssessment.nearestShoalNm());
        writeObstruction(routeNode, routeAssessment.nearestObstruction());

        ArrayNode assumptions = payload.putArray("assumptions");
        assumptions.add("instantaneous_course_change");
        assumptions.add("constant_speed");
        assumptions.add("target_state_ignored");
        assumptions.add("tidal_level_not_modeled");
        assumptions.add("discrete_route_sampling");

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private List<GeoPoint> sampleRoute(ShipStatus ownShip, double assumedCogDeg, double lookaheadMin) {
        int sampleCount = Math.min(MAX_SAMPLE_COUNT, Math.max(1, (int) Math.ceil(lookaheadMin * 60.0 / 30.0)));
        double stepSeconds = lookaheadMin * 60.0 / sampleCount;
        double[] velocity = GeoUtils.toVelocity(ownShip.getSog(), assumedCogDeg);
        List<GeoPoint> points = new ArrayList<>(sampleCount);
        for (int index = 1; index <= sampleCount; index++) {
            double tSeconds = stepSeconds * index;
            double[] displaced = GeoUtils.displace(
                    ownShip.getLatitude(),
                    ownShip.getLongitude(),
                    velocity[0] * tSeconds,
                    velocity[1] * tSeconds
            );
            points.add(new GeoPoint(displaced[0], displaced[1]));
        }
        return points;
    }

    private String validateOwnShip(ShipStatus ownShip) {
        if (ownShip == null) {
            return "Frozen own-ship state is not available in this snapshot";
        }
        if (!isFinite(ownShip.getLatitude()) || !isFinite(ownShip.getLongitude())
                || !isFinite(ownShip.getSog()) || !isFinite(ownShip.getCog())) {
            return "Frozen own-ship state must include finite lat/lon/sog/cog values";
        }
        return null;
    }

    private Double numberArg(ToolCall call, String key) {
        JsonNode node = call.arguments().get(key);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        return Double.isFinite(value) ? value : null;
    }

    private boolean isFinite(Double value) {
        return value != null && Double.isFinite(value);
    }

    private double normalize360(double degrees) {
        return ((degrees % 360.0) + 360.0) % 360.0;
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private void writeObstruction(ObjectNode payload, NearestObstructionSummary obstruction) {
        if (obstruction == null) {
            payload.putNull("nearest_obstruction");
            return;
        }
        ObjectNode node = payload.putObject("nearest_obstruction");
        putNullable(node, "category", obstruction.category());
        putNullable(node, "distance_nm", obstruction.distanceNm());
        putNullable(node, "bearing_deg", obstruction.bearingDeg());
    }

    private void putNullable(ObjectNode node, String key, String value) {
        if (value != null) node.put(key, value);
        else node.putNull(key);
    }

    private void putNullable(ObjectNode node, String key, Double value) {
        if (value != null) node.put(key, value);
        else node.putNull(key);
    }

    private void putNullable(ObjectNode node, String key, Integer value) {
        if (value != null) node.put(key, value);
        else node.putNull(key);
    }
}
