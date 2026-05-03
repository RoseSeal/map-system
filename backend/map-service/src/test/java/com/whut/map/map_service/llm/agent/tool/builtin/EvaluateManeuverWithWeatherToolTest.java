package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.risk.config.WeatherRiskProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluateManeuverWithWeatherToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WeatherRiskProperties weatherRiskProperties = new WeatherRiskProperties();
    private final WeatherAlertProperties weatherAlertProperties = new WeatherAlertProperties();
    private final EvaluateManeuverWithWeatherTool tool = new EvaluateManeuverWithWeatherTool(
            MAPPER, weatherRiskProperties, weatherAlertProperties
    );

    @Test
    void lowVisibilityFlagTrueWithDecelerationRecommendation() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG")
                .visibilityNm(0.8)
                .activeAlerts(List.of("LOW_VISIBILITY"))
                .build();
        ShipStatus ownShip = ShipStatus.builder()
                .id("own").role(ShipRole.OWN_SHIP)
                .longitude(120.0).latitude(30.0)
                .sog(10.0).cog(90.0)
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                1L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of(),
                ownShip,
                Map.of()
        );

        ObjectNode args = MAPPER.createObjectNode().put("speed_change_kn", -2.0);
        ToolCall call = new ToolCall("c1", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.get("weather_flags").get("low_visibility").asBoolean()).isTrue();
        assertThat(payload.get("recommendations").toString()).contains("安全航速");
    }

    @Test
    void strongCurrentFlagTrueWithMarginRecommendation() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("CLEAR")
                .surfaceCurrentSpeedKn(3.0)
                .surfaceCurrentSetDeg(90)
                .activeAlerts(List.of("STRONG_CURRENT_SET"))
                .build();
        ShipStatus ownShip = ShipStatus.builder()
                .id("own").role(ShipRole.OWN_SHIP)
                .longitude(120.0).latitude(30.0)
                .sog(10.0).cog(90.0)
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                2L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of(),
                ownShip,
                Map.of()
        );

        ObjectNode args = MAPPER.createObjectNode().put("course_change_deg", -30.0);
        ToolCall call = new ToolCall("c2", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("weather_flags").get("strong_current").asBoolean()).isTrue();
        assertThat(payload.get("recommendations").toString()).contains("加大裕量");
    }

    @Test
    void clearWeatherWithNoAlertsReturnsEmptyRecommendations() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("CLEAR")
                .visibilityNm(10.0)
                .activeAlerts(List.of())
                .build();
        ShipStatus ownShip = ShipStatus.builder()
                .id("own").role(ShipRole.OWN_SHIP)
                .longitude(120.0).latitude(30.0)
                .sog(10.0).cog(90.0)
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                3L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of(),
                ownShip,
                Map.of()
        );

        ObjectNode args = MAPPER.createObjectNode().put("course_change_deg", 10.0);
        ToolCall call = new ToolCall("c3", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.get("recommendations").size()).isEqualTo(0);
    }

    @Test
    void lookaheadMinNonZeroReturnsInvalidArgument() {
        AgentSnapshot snapshot = new AgentSnapshot(4L, null, Map.of());
        ObjectNode args = MAPPER.createObjectNode().put("lookahead_min", 5.0);
        ToolCall call = new ToolCall("c4", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void returnsNoWeatherDataWhenWeatherIsNull() {
        AgentSnapshot snapshot = new AgentSnapshot(
                5L,
                LlmRiskContext.builder().build(),
                Map.of()
        );
        ToolCall call = new ToolCall("c5", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("NO_WEATHER_DATA");
    }

    @Test
    void negativeResultingSpeedReturnsInvalidArgument() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG").visibilityNm(0.8).activeAlerts(List.of()).build();
        ShipStatus ownShip = ShipStatus.builder()
                .id("own").role(ShipRole.OWN_SHIP)
                .longitude(120.0).latitude(30.0)
                .sog(5.0).cog(90.0)
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                7L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of(),
                ownShip,
                Map.of()
        );

        ObjectNode args = MAPPER.createObjectNode().put("speed_change_kn", -10.0);
        ToolCall call = new ToolCall("c7", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void ownShipUnavailableWithManeuverParamsReturnsError() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG").visibilityNm(0.8).activeAlerts(List.of()).build();
        AgentSnapshot snapshot = new AgentSnapshot(
                8L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of(),
                null,
                Map.of()
        );

        ObjectNode args = MAPPER.createObjectNode().put("speed_change_kn", -2.0);
        ToolCall call = new ToolCall("c8", tool.getDefinition().name(), args);

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("OWN_SHIP_UNAVAILABLE");
    }

    @Test
    void proposedStateAbsentWhenNoManeuverParamsProvided() {
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .weatherCode("FOG")
                .visibilityNm(0.8)
                .activeAlerts(List.of())
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(
                6L,
                LlmRiskContext.builder().weather(weather).build(),
                Map.of()
        );
        ToolCall call = new ToolCall("c6", tool.getDefinition().name(), MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, snapshot);
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.has("proposed_state")).isFalse();
        assertThat(payload.has("effective_weather")).isTrue();
        assertThat(payload.has("weather_flags")).isTrue();
    }
}
