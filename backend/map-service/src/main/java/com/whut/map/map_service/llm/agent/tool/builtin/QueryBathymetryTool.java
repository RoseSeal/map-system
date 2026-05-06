package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.tool.AgentTool;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import org.springframework.stereotype.Component;

@Component
public class QueryBathymetryTool implements AgentTool {

    private final ObjectMapper mapper;
    private final HydrologyContextService hydrologyContextService;
    private final SafetyContourStateHolder safetyContourStateHolder;
    private final ToolDefinition definition;

    public QueryBathymetryTool(
            ObjectMapper mapper,
            HydrologyContextService hydrologyContextService,
            SafetyContourStateHolder safetyContourStateHolder
    ) {
        this.mapper = mapper;
        this.hydrologyContextService = hydrologyContextService;
        this.safetyContourStateHolder = safetyContourStateHolder;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("lon")
                .put("type", "number")
                .put("description", "Required. Longitude in degrees, range [-180, 180].");
        properties.putObject("lat")
                .put("type", "number")
                .put("description", "Required. Latitude in degrees, range [-90, 90].");
        properties.putObject("radius_nm")
                .put("type", "number")
                .put("description", "Required. Requested hydrology search radius in nautical miles, range (0, 5].");
        schema.putArray("required").add("lon").add("lat").add("radius_nm");

        this.definition = new ToolDefinition(
                AgentToolNames.QUERY_BATHYMETRY,
                "Query current bathymetry and nearest obstruction facts near a point. Returns hydrology source fields only; does not read or change risk state.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        Double lon = numberArg(call, "lon");
        Double lat = numberArg(call, "lat");
        Double radiusNm = numberArg(call, "radius_nm");
        if (lon == null || lat == null || radiusNm == null) {
            return errorResult(call, "INVALID_ARGUMENT", "lon, lat and radius_nm are required numeric fields");
        }
        if (lon < -180.0 || lon > 180.0) {
            return errorResult(call, "INVALID_ARGUMENT", "lon must be in range [-180, 180]");
        }
        if (lat < -90.0 || lat > 90.0) {
            return errorResult(call, "INVALID_ARGUMENT", "lat must be in range [-90, 90]");
        }
        if (radiusNm <= 0.0 || radiusNm > 5.0) {
            return errorResult(call, "INVALID_ARGUMENT", "radius_nm must be in range (0, 5]");
        }

        double safetyContourMeters = safetyContourStateHolder.getCurrentDepthMeters();
        HydrologyContext hydrology = hydrologyContextService.resolve(lat, lon, safetyContourMeters, radiusNm);
        if (hydrology == null) {
            ObjectNode payload = mapper.createObjectNode()
                    .put("status", "NO_HYDROLOGY_DATA")
                    .put("source", "hydrology")
                    .put("message", "Hydrology data unavailable for the requested position");
            return new ToolResult(call.callId(), call.toolName(), payload);
        }

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("source", "hydrology")
                .put("radius_nm", radiusNm)
                .put("safety_contour_m", safetyContourMeters);
        payload.putObject("position")
                .put("lon", lon)
                .put("lat", lat);
        payload.putObject("effective_search_radius_nm")
                .put("shoal", hydrologyContextService.effectiveShoalSearchRadiusNm(radiusNm))
                .put("obstruction", hydrologyContextService.effectiveObstructionSearchRadiusNm(radiusNm));
        putNullable(payload, "own_ship_min_depth_m", hydrology.ownShipMinDepthM());
        putNullable(payload, "nearest_shoal_nm", hydrology.nearestShoalNm());
        writeObstruction(payload, hydrology.nearestObstruction());
        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private Double numberArg(ToolCall call, String key) {
        JsonNode node = call.arguments().get(key);
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        double value = node.doubleValue();
        return Double.isFinite(value) ? value : null;
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
