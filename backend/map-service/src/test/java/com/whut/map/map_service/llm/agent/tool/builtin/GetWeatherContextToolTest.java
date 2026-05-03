package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GetWeatherContextToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GetWeatherContextTool tool = new GetWeatherContextTool(MAPPER);

    @Test
    void returnsOkWithCorrectFieldsWhenWeatherPresent() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG")
                .visibilityNm(0.8)
                .windSpeedKn(12.0)
                .windDirectionFromDeg(225)
                .surfaceCurrentSpeedKn(1.4)
                .surfaceCurrentSetDeg(90)
                .seaState(3)
                .sourceZoneId("fog-bank-east")
                .activeAlerts(List.of("LOW_VISIBILITY"))
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                42L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of()
        );
        ToolCall call = new ToolCall("c1", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.get("snapshot_version").asLong()).isEqualTo(42L);
        assertThat(payload.get("weather_code").asText()).isEqualTo("FOG");
        assertThat(payload.get("visibility_nm").asDouble()).isEqualTo(0.8);
        assertThat(payload.get("wind_speed_kn").asDouble()).isEqualTo(12.0);
        assertThat(payload.get("wind_direction_from_deg").asInt()).isEqualTo(225);
        assertThat(payload.get("surface_current_speed_kn").asDouble()).isEqualTo(1.4);
        assertThat(payload.get("surface_current_set_deg").asInt()).isEqualTo(90);
        assertThat(payload.get("sea_state").asInt()).isEqualTo(3);
        assertThat(payload.get("source_zone_id").asText()).isEqualTo("fog-bank-east");
        assertThat(payload.get("active_alerts").get(0).asText()).isEqualTo("LOW_VISIBILITY");
    }

    @Test
    void returnsNoWeatherDataWhenWeatherIsNull() {
        AgentSnapshot snapshot = new AgentSnapshot(
                5L,
                LlmRiskContext.builder().build(),
                Map.of()
        );
        ToolCall call = new ToolCall("c2", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("NO_WEATHER_DATA");
    }

    @Test
    void returnsNoWeatherDataWhenRiskContextIsNull() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, Map.of());
        ToolCall call = new ToolCall("c3", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("NO_WEATHER_DATA");
    }

    @Test
    void activeAlertsContainsLowVisibilityWhenSet() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG")
                .visibilityNm(0.8)
                .activeAlerts(List.of("LOW_VISIBILITY"))
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                7L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of()
        );
        ToolCall call = new ToolCall("c4", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("active_alerts").toString()).contains("LOW_VISIBILITY");
    }
}
