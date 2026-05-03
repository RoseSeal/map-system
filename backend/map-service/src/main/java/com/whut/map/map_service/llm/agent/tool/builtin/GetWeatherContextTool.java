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
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GetWeatherContextTool implements AgentTool {

    private final ObjectMapper mapper;
    private final ToolDefinition definition;

    public GetWeatherContextTool(ObjectMapper mapper) {
        this.mapper = mapper;
        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties");
        this.definition = new ToolDefinition(
                AgentToolNames.GET_WEATHER_CONTEXT,
                "Returns the effective weather at own-ship position from the frozen snapshot. Weather flags are independent of the risk engine adjustment toggles.",
                schema
        );
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolCall call, AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null) {
            return noWeatherResult(call);
        }
        LlmRiskWeatherContext weather = snapshot.riskContext().getWeather();
        if (weather == null) {
            return noWeatherResult(call);
        }

        ObjectNode payload = mapper.createObjectNode()
                .put("status", "OK")
                .put("snapshot_version", snapshot.snapshotVersion());

        putNullable(payload, "weather_code", weather.getWeatherCode());
        putNullable(payload, "visibility_nm", weather.getVisibilityNm());
        putNullable(payload, "wind_speed_kn", weather.getWindSpeedKn());
        putNullable(payload, "wind_direction_from_deg", weather.getWindDirectionFromDeg());
        putNullable(payload, "surface_current_speed_kn", weather.getSurfaceCurrentSpeedKn());
        putNullable(payload, "surface_current_set_deg", weather.getSurfaceCurrentSetDeg());
        putNullable(payload, "sea_state", weather.getSeaState());
        putNullable(payload, "source_zone_id", weather.getSourceZoneId());

        List<String> alerts = weather.getActiveAlerts();
        ArrayNode alertsNode = payload.putArray("active_alerts");
        if (alerts != null) {
            alerts.forEach(alertsNode::add);
        }

        return new ToolResult(call.callId(), call.toolName(), payload);
    }

    private ToolResult noWeatherResult(ToolCall call) {
        ObjectNode payload = mapper.createObjectNode()
                .put("status", "NO_WEATHER_DATA")
                .put("message", "Weather context unavailable: no fresh signal or stale snapshot");
        return new ToolResult(call.callId(), call.toolName(), payload);
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
