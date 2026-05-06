package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.chart.dto.HydrologyRouteAssessment;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluateManeuverHydrologyToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void missingOwnShipReturnsOwnShipUnavailable() {
        EvaluateManeuverHydrologyTool tool = new EvaluateManeuverHydrologyTool(
                MAPPER, mock(HydrologyContextService.class), new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("course_change_deg", 20.0)
                .put("lookahead_min", 10.0);

        ToolResult result = tool.execute(
                new ToolCall("c1", tool.getDefinition().name(), args),
                new AgentSnapshot(1L, null, Map.of(), null, Map.of())
        );
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("OWN_SHIP_UNAVAILABLE");
    }

    @Test
    void validManeuverReturnsRouteAssessmentAndAssumptions() {
        HydrologyContextService hydrologyService = mock(HydrologyContextService.class);
        when(hydrologyService.evaluateRoute(any(), anyDouble(), anyDouble()))
                .thenReturn(new HydrologyRouteAssessment(
                        8.3,
                        true,
                        0.0,
                        new NearestObstructionSummary("WRECK", 0.42, 84),
                        12,
                        12,
                        true
                ));
        EvaluateManeuverHydrologyTool tool = new EvaluateManeuverHydrologyTool(
                MAPPER, hydrologyService, new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("course_change_deg", 20.0)
                .put("lookahead_min", 10.0);

        ToolResult result = tool.execute(new ToolCall("c2", tool.getDefinition().name(), args), snapshotWithOwnShip());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.get("source").asText()).isEqualTo("hydrology");
        assertThat(payload.get("route_hydrology").get("crosses_shoal").asBoolean()).isTrue();
        assertThat(payload.get("route_hydrology").get("sample_count").asInt()).isEqualTo(12);
        assertThat(payload.get("route_hydrology").get("resolved_sample_count").asInt()).isEqualTo(12);
        assertThat(payload.get("route_hydrology").get("data_complete").asBoolean()).isTrue();
        assertThat(payload.get("route_hydrology").get("nearest_obstruction").get("category").asText()).isEqualTo("WRECK");
        assertThat(payload.get("assumptions").toString()).contains("tidal_level_not_modeled");
        assertThat(payload.has("delta_dcpa_nm")).isFalse();
    }

    @Test
    void routeWithoutResolvedHydrologyReturnsNoHydrologyData() {
        HydrologyContextService hydrologyService = mock(HydrologyContextService.class);
        when(hydrologyService.evaluateRoute(any(), anyDouble(), anyDouble()))
                .thenReturn(new HydrologyRouteAssessment(null, false, null, null, 12, 0, false));
        EvaluateManeuverHydrologyTool tool = new EvaluateManeuverHydrologyTool(
                MAPPER, hydrologyService, new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("course_change_deg", 20.0)
                .put("lookahead_min", 10.0);

        ToolResult result = tool.execute(new ToolCall("c5", tool.getDefinition().name(), args), snapshotWithOwnShip());
        ObjectNode payload = (ObjectNode) result.payload();
        ObjectNode routeHydrology = (ObjectNode) payload.get("route_hydrology");

        assertThat(payload.get("status").asText()).isEqualTo("NO_HYDROLOGY_DATA");
        assertThat(payload.get("source").asText()).isEqualTo("hydrology");
        assertThat(payload.get("message").asText()).contains("unavailable");
        assertThat(routeHydrology.get("sample_count").asInt()).isEqualTo(12);
        assertThat(routeHydrology.get("resolved_sample_count").asInt()).isZero();
        assertThat(routeHydrology.get("data_complete").asBoolean()).isFalse();
        assertThat(routeHydrology.get("crosses_shoal").isNull()).isTrue();
    }

    @Test
    void invalidCourseChangeReturnsInvalidArgument() {
        EvaluateManeuverHydrologyTool tool = new EvaluateManeuverHydrologyTool(
                MAPPER, mock(HydrologyContextService.class), new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("course_change_deg", 181.0)
                .put("lookahead_min", 10.0);

        ToolResult result = tool.execute(new ToolCall("c3", tool.getDefinition().name(), args), snapshotWithOwnShip());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void invalidLookaheadReturnsInvalidArgument() {
        EvaluateManeuverHydrologyTool tool = new EvaluateManeuverHydrologyTool(
                MAPPER, mock(HydrologyContextService.class), new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("course_change_deg", 20.0)
                .put("lookahead_min", 31.0);

        ToolResult result = tool.execute(new ToolCall("c4", tool.getDefinition().name(), args), snapshotWithOwnShip());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private AgentSnapshot snapshotWithOwnShip() {
        ShipStatus ownShip = ShipStatus.builder()
                .id("own").role(ShipRole.OWN_SHIP)
                .longitude(120.0).latitude(30.0)
                .sog(10.0).cog(90.0).heading(90.0)
                .build();
        return new AgentSnapshot(2L, null, Map.of(), ownShip, Map.of());
    }
}
