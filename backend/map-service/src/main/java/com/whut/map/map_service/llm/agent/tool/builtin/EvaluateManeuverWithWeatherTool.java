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
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.risk.config.WeatherRiskProperties;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class EvaluateManeuverWithWeatherTool implements AgentTool {

    private final ObjectMapper mapper;
    private final WeatherRiskProperties weatherRiskProperties;
    private final WeatherAlertProperties weatherAlertProperties;
    private final ToolDefinition definition;

    public EvaluateManeuverWithWeatherTool(
            ObjectMapper mapper,
            WeatherRiskProperties weatherRiskProperties,
            WeatherAlertProperties weatherAlertProperties
    ) {
        this.mapper = mapper;
        this.weatherRiskProperties = weatherRiskProperties;
        this.weatherAlertProperties = weatherAlertProperties;

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("target_id")
                .put("type", "string")
                .put("description", "Optional. Target ship ID the maneuver is intended for (for contextual description only; not used in geometry calculation).");
        properties.putObject("course_change_deg")
                .put("type", "number")
                .put("description", "Optional. Proposed course change in degrees; positive = starboard, negative = port.");
        properties.putObject("speed_change_kn")
                .put("type", "number")
                .put("description", "Optional. Proposed speed change in knots; negative = decelerate.");
        properties.putObject("lookahead_min")
                .put("type", "number")
                .put("description", "Optional. Reserved for future use; must be 0 or omitted in v1.0.");

        this.definition = new ToolDefinition(
                AgentToolNames.EVALUATE_MANEUVER_WITH_WEATHER,
                "Evaluate the effect of current weather on a proposed maneuver. Returns weather flags and recommendations. Does not compute CPA geometry — use evaluate_maneuver for that. Weather flags are independent of the risk engine adjustment toggles.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        JsonNode lookaheadNode = call.arguments().get("lookahead_min");
        if (lookaheadNode != null && !lookaheadNode.isNull()) {
            if (!lookaheadNode.isNumber() || lookaheadNode.doubleValue() != 0) {
                return errorResult(call, "INVALID_ARGUMENT",
                        "lookahead_min is reserved for future use; must be 0 or omitted in v1.0");
            }
        }

        if (snapshot.riskContext() == null) {
            return noWeatherResult(call);
        }
        LlmRiskWeatherContext weather = snapshot.riskContext().getWeather();
        if (weather == null) {
            return noWeatherResult(call);
        }

        ShipStatus ownShip = snapshot.frozenOwnShip();

        Double courseChangeDeg = doubleArg(call, "course_change_deg");
        Double speedChangeKn = doubleArg(call, "speed_change_kn");

        boolean hasManeuverParams = courseChangeDeg != null || speedChangeKn != null;
        if (hasManeuverParams && ownShip == null) {
            return errorResult(call, "OWN_SHIP_UNAVAILABLE",
                    "Frozen own-ship state is not available; cannot compute proposed state without baseline course/speed");
        }

        double currentSog = ownShip != null ? ownShip.getSog() : 0.0;
        double currentCog = ownShip != null ? ownShip.getCog() : 0.0;

        double speedAfter = speedChangeKn != null ? currentSog + speedChangeKn : currentSog;
        if (speedChangeKn != null && speedAfter < 0) {
            return errorResult(call, "INVALID_ARGUMENT",
                    "speed_change_kn would result in negative sog (" + speedAfter + " kn)");
        }
        double courseAfter = courseChangeDeg != null
                ? ((currentCog + courseChangeDeg) % 360 + 360) % 360
                : currentCog;

        boolean lowVisibility = weather.getVisibilityNm() != null
                && weather.getVisibilityNm() < weatherRiskProperties.getVisibility().getLowVisNm();
        boolean strongCurrent = weather.getSurfaceCurrentSpeedKn() != null
                && weather.getSurfaceCurrentSpeedKn() > weatherAlertProperties.getStrongCurrentSetKn();
        boolean stormConditions = "STORM".equals(weather.getWeatherCode())
                || (weather.getSeaState() != null && weather.getSeaState() >= weatherRiskProperties.getStorm().getSeaStateThreshold());

        List<String> recommendations = buildRecommendations(weather, lowVisibility, strongCurrent, stormConditions, speedAfter);

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());

        ObjectNode effectiveWeatherNode = payload.putObject("effective_weather");
        putNullable(effectiveWeatherNode, "weather_code", weather.getWeatherCode());
        putNullable(effectiveWeatherNode, "visibility_nm", weather.getVisibilityNm());
        putNullable(effectiveWeatherNode, "surface_current_speed_kn", weather.getSurfaceCurrentSpeedKn());
        putNullable(effectiveWeatherNode, "surface_current_set_deg", weather.getSurfaceCurrentSetDeg());
        putNullable(effectiveWeatherNode, "sea_state", weather.getSeaState());

        if (courseChangeDeg != null || speedChangeKn != null) {
            ObjectNode proposedState = payload.putObject("proposed_state");
            if (courseChangeDeg != null) proposedState.put("course_after_deg", courseAfter);
            if (speedChangeKn != null) proposedState.put("speed_after_kn", speedAfter);
        }

        payload.putObject("weather_flags")
                .put("low_visibility", lowVisibility)
                .put("strong_current", strongCurrent)
                .put("storm_conditions", stormConditions);

        ArrayNode recNode = payload.putArray("recommendations");
        recommendations.forEach(recNode::add);

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private List<String> buildRecommendations(
            LlmRiskWeatherContext weather,
            boolean lowVisibility,
            boolean strongCurrent,
            boolean stormConditions,
            double speedAfter
    ) {
        List<String> recs = new ArrayList<>();
        if (lowVisibility) {
            if (speedAfter > 0) {
                recs.add(String.format("能见度 %.1f nm，低于 %.1f nm 阈值；建议将航速减至安全航速",
                        weather.getVisibilityNm(), weatherRiskProperties.getVisibility().getLowVisNm()));
            } else {
                recs.add("航速已减为零，满足低能见度安全航速要求");
            }
        }
        if (strongCurrent) {
            String currentDesc = String.format("%.1f 节", weather.getSurfaceCurrentSpeedKn());
            if (weather.getSurfaceCurrentSetDeg() != null) {
                currentDesc += String.format("（%d°）", weather.getSurfaceCurrentSetDeg());
            }
            recs.add("水流 " + currentDesc + "；建议机动量适当加大裕量以补偿流场偏移");
        }
        if (stormConditions) {
            String seaDesc = weather.getSeaState() != null ? ("海况 " + weather.getSeaState() + " 级") : "恶劣海况";
            recs.add(seaDesc + "；极端天气下建议减小机动幅度或等待改善");
        }
        return recs;
    }

    private ToolResult noWeatherResult(ToolCall call) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "NO_WEATHER_DATA")
                .put("message", "Weather context unavailable: no fresh signal or stale snapshot");
        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private ToolResult errorResult(ToolCall call, String errorCode, String message) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "ERROR")
                .put("error_code", errorCode)
                .put("message", message);
        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private Double doubleArg(ToolCall call, String key) {
        JsonNode node = call.arguments().get(key);
        if (node == null || node.isNull() || !node.isNumber()) return null;
        return node.doubleValue();
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
