package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.agent.casegraph.CaseGraphUnavailableException;
import com.whut.map.map_service.llm.agent.casegraph.CaseQueryMode;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCase;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseQuery;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseQueryPort;
import com.whut.map.map_service.llm.agent.casegraph.HistoricalCaseResult;
import com.whut.map.map_service.llm.agent.graph.VisibilityCondition;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryHistoricalCaseGraphToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private HistoricalCaseQueryPort caseQueryPort;

    private QueryHistoricalCaseGraphTool tool;

    @BeforeEach
    void setUp() {
        tool = new QueryHistoricalCaseGraphTool(MAPPER, caseQueryPort);
    }

    @Test
    void targetIdDerivesQueryAndMapsCases() {
        when(caseQueryPort.findSimilarCases(any())).thenReturn(resultWithCases());
        AgentSnapshot snapshot = snapshot(
                LlmRiskWeatherContext.builder()
                        .visibilityNm(0.8)
                        .activeAlerts(List.of("LOW_VISIBILITY"))
                        .build()
        );
        ObjectNode args = MAPPER.createObjectNode()
                .put("target_id", "t1")
                .put("water_area", "狭水道");

        ToolResult result = tool.execute(call(args), snapshot);

        ArgumentCaptor<HistoricalCaseQuery> captor = ArgumentCaptor.forClass(HistoricalCaseQuery.class);
        verify(caseQueryPort).findSimilarCases(captor.capture());
        HistoricalCaseQuery query = captor.getValue();
        assertThat(query.encounterType()).isEqualTo(EncounterType.CROSSING);
        assertThat(query.ownShipRole()).isEqualTo(OwnShipRole.GIVE_WAY);
        assertThat(query.riskLevel()).isEqualTo(RiskLevel.ALARM);
        assertThat(query.visibilityCondition()).isEqualTo(VisibilityCondition.RESTRICTED_VISIBILITY);
        assertThat(query.targetCount()).isEqualTo(1);
        assertThat(result.payload().at("/cases/0/case_id").asText()).isEqualTo("H-07");
        assertThat(result.payload().at("/cases/0/colregs_rules/0").asText()).isEqualTo("Rule 13");
        assertThat(result.payload().at("/cases/0/lesson").asText()).isEqualTo("及早建立安全通过距离");
    }

    @Test
    void explicitArgumentsOverrideSnapshotDerivedValues() {
        when(caseQueryPort.findSimilarCases(any())).thenReturn(emptyResult());
        ObjectNode args = MAPPER.createObjectNode()
                .put("target_id", "t1")
                .put("encounter_type", "HEAD_ON")
                .put("own_ship_role", "MUTUAL_ACTION")
                .put("visibility_condition", "OPEN_VISIBILITY");

        tool.execute(call(args), snapshot(null));

        ArgumentCaptor<HistoricalCaseQuery> captor = ArgumentCaptor.forClass(HistoricalCaseQuery.class);
        verify(caseQueryPort).findSimilarCases(captor.capture());
        assertThat(captor.getValue().encounterType()).isEqualTo(EncounterType.HEAD_ON);
        assertThat(captor.getValue().ownShipRole()).isEqualTo(OwnShipRole.MUTUAL_ACTION);
        assertThat(captor.getValue().visibilityCondition()).isEqualTo(VisibilityCondition.OPEN_VISIBILITY);
    }

    @Test
    void visibilityIsUnknownWithoutWeather() {
        when(caseQueryPort.findSimilarCases(any())).thenReturn(emptyResult());

        tool.execute(call(MAPPER.createObjectNode().put("query_text", "similar case")), snapshot(null));

        ArgumentCaptor<HistoricalCaseQuery> captor = ArgumentCaptor.forClass(HistoricalCaseQuery.class);
        verify(caseQueryPort).findSimilarCases(captor.capture());
        assertThat(captor.getValue().visibilityCondition()).isEqualTo(VisibilityCondition.UNKNOWN);
    }

    @Test
    void visibilityIsOpenWithoutLowVisibilityAlert() {
        when(caseQueryPort.findSimilarCases(any())).thenReturn(emptyResult());
        LlmRiskWeatherContext weather = LlmRiskWeatherContext.builder()
                .visibilityNm(5.0)
                .activeAlerts(List.of())
                .build();

        tool.execute(call(MAPPER.createObjectNode().put("query_text", "similar case")), snapshot(weather));

        ArgumentCaptor<HistoricalCaseQuery> captor = ArgumentCaptor.forClass(HistoricalCaseQuery.class);
        verify(caseQueryPort).findSimilarCases(captor.capture());
        assertThat(captor.getValue().visibilityCondition()).isEqualTo(VisibilityCondition.OPEN_VISIBILITY);
    }

    @Test
    void unavailableSidecarReturnsErrorPayload() {
        when(caseQueryPort.findSimilarCases(any())).thenThrow(new CaseGraphUnavailableException("down"));

        ToolResult result = tool.execute(
                call(MAPPER.createObjectNode().put("query_text", "similar case")),
                snapshot(null)
        );

        assertThat(result.payload().path("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().path("error_code").asText()).isEqualTo("CASE_GRAPH_UNAVAILABLE");
    }

    @Test
    void emptyFeaturesReturnInvalidArgumentWithoutCallingPort() {
        ToolResult result = tool.execute(call(MAPPER.createObjectNode()), snapshot(null));

        assertThat(result.payload().path("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
        verify(caseQueryPort, never()).findSimilarCases(any());
    }

    @Test
    void missingTargetReturnsTargetNotFoundWithoutCallingPort() {
        ToolResult result = tool.execute(
                call(MAPPER.createObjectNode().put("target_id", "missing")),
                snapshot(null)
        );

        assertThat(result.payload().path("error_code").asText()).isEqualTo("TARGET_NOT_FOUND");
        verify(caseQueryPort, never()).findSimilarCases(any());
    }

    @Test
    void emptyCasesRemainSuccessful() {
        when(caseQueryPort.findSimilarCases(any())).thenReturn(emptyResult());

        ToolResult result = tool.execute(
                call(MAPPER.createObjectNode().put("query_text", "similar case")),
                snapshot(null)
        );

        assertThat(result.payload().path("status").asText()).isEqualTo("OK");
        assertThat(result.payload().path("cases")).isEmpty();
    }

    private ToolCall call(ObjectNode args) {
        return new ToolCall("call-1", tool.getDefinition().name(), args);
    }

    private AgentSnapshot snapshot(LlmRiskWeatherContext weather) {
        LlmRiskTargetContext target = LlmRiskTargetContext.builder()
                .targetId("t1")
                .riskLevel(RiskLevel.ALARM)
                .build();
        EncounterClassificationResult encounter = EncounterClassificationResult.builder()
                .targetId("t1")
                .encounterType(EncounterType.CROSSING)
                .ownShipRole(OwnShipRole.GIVE_WAY)
                .build();
        TargetDerivedSnapshot derived = new TargetDerivedSnapshot(
                "t1", null, null, null, encounter, null);
        return new AgentSnapshot(
                42L,
                LlmRiskContext.builder().targets(List.of(target)).weather(weather).build(),
                Map.of("t1", derived)
        );
    }

    private HistoricalCaseResult emptyResult() {
        return new HistoricalCaseResult(CaseQueryMode.LOCAL, "query", List.of(), null, null);
    }

    private HistoricalCaseResult resultWithCases() {
        HistoricalCase historicalCase = new HistoricalCase(
                "H-07",
                "受限能见度追越险情",
                0.91,
                "狭水道",
                "RESTRICTED_VISIBILITY",
                "GIVE_WAY",
                "OVERTAKING",
                "ALARM",
                "一艘追越船未保持足够横距",
                List.of("Rule 13"),
                "NEAR_MISS",
                "及早右转并减速",
                "及早建立安全通过距离"
        );
        return new HistoricalCaseResult(
                CaseQueryMode.LOCAL, "query", List.of(historicalCase), null, null);
    }
}
