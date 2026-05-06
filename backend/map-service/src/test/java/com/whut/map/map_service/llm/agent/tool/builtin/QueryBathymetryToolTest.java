package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryBathymetryToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validPositionReturnsHydrologySourceFacts() {
        HydrologyContextService hydrologyService = mock(HydrologyContextService.class);
        when(hydrologyService.resolve(anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new HydrologyContext(8.3, 0.0, new NearestObstructionSummary("WRECK", 0.71, 37)));
        when(hydrologyService.effectiveShoalSearchRadiusNm(1.0)).thenReturn(1.0);
        when(hydrologyService.effectiveObstructionSearchRadiusNm(1.0)).thenReturn(1.0);
        QueryBathymetryTool tool = new QueryBathymetryTool(
                MAPPER, hydrologyService, new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("lon", -73.91)
                .put("lat", 40.59)
                .put("radius_nm", 1.0);

        ToolResult result = tool.execute(new ToolCall("c1", tool.getDefinition().name(), args), snapshot());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("OK");
        assertThat(payload.get("source").asText()).isEqualTo("hydrology");
        assertThat(payload.get("own_ship_min_depth_m").asDouble()).isEqualTo(8.3);
        assertThat(payload.get("nearest_obstruction").get("category").asText()).isEqualTo("WRECK");
    }

    @Test
    void missingHydrologyDataReturnsNoHydrologyDataWithoutInventingDepth() {
        HydrologyContextService hydrologyService = mock(HydrologyContextService.class);
        when(hydrologyService.resolve(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(null);
        QueryBathymetryTool tool = new QueryBathymetryTool(
                MAPPER, hydrologyService, new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("lon", -73.91)
                .put("lat", 40.59)
                .put("radius_nm", 1.0);

        ToolResult result = tool.execute(new ToolCall("c2", tool.getDefinition().name(), args), snapshot());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("NO_HYDROLOGY_DATA");
        assertThat(payload.has("own_ship_min_depth_m")).isFalse();
    }

    @Test
    void outOfRangeArgumentsReturnInvalidArgument() {
        QueryBathymetryTool tool = new QueryBathymetryTool(
                MAPPER, mock(HydrologyContextService.class), new SafetyContourStateHolder(new RiskObjectMetaProperties()));
        ObjectNode args = MAPPER.createObjectNode()
                .put("lon", -73.91)
                .put("lat", 95.0)
                .put("radius_nm", 1.0);

        ToolResult result = tool.execute(new ToolCall("c3", tool.getDefinition().name(), args), snapshot());
        ObjectNode payload = (ObjectNode) result.payload();

        assertThat(payload.get("status").asText()).isEqualTo("ERROR");
        assertThat(payload.get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    private AgentSnapshot snapshot() {
        return new AgentSnapshot(1L, null, Map.of());
    }
}
