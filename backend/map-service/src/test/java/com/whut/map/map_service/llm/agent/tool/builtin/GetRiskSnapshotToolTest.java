package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GetRiskSnapshotToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GetRiskSnapshotTool tool = new GetRiskSnapshotTool(MAPPER);

    private ToolCall noArgCall() {
        return new ToolCall("cid-1", "get_risk_snapshot", MAPPER.createObjectNode());
    }

    private LlmRiskOwnShipContext ownShip() {
        return LlmRiskOwnShipContext.builder()
                .id("own-ship").longitude(114.35).latitude(30.54).sog(10.4).cog(83.1).heading(82.0).build();
    }

    private LlmRiskTargetContext target(String id, RiskLevel level, double score, boolean approaching, double tcpa, double dist) {
        return LlmRiskTargetContext.builder()
                .targetId(id).riskLevel(level).riskScore(score).approaching(approaching)
                .tcpaSec(tcpa).currentDistanceNm(dist).dcpaNm(0.1)
                .longitude(114.36).latitude(30.55).speedKn(8.0).courseDeg(200.0)
                .encounterType(EncounterType.CROSSING).build();
    }

    @Test
    void nullRiskContextReturnsSnapshotIncomplete() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, null);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("SNAPSHOT_INCOMPLETE");
    }

    @Test
    void emptyTargetListReturnsZeroCountsAndSafeLevel() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(Collections.emptyList()).build();
        AgentSnapshot snapshot = new AgentSnapshot(5L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);

        ObjectNode p = result.payload();
        assertThat(p.get("status").asText()).isEqualTo("OK");
        assertThat(p.get("snapshot_version").asLong()).isEqualTo(5L);
        assertThat(p.get("target_count").asInt()).isEqualTo(0);
        assertThat(p.get("approaching_target_count").asInt()).isEqualTo(0);
        assertThat(p.get("highest_risk_level").asText()).isEqualTo("SAFE");
        assertThat(p.get("top_risk_target_ids")).isEmpty();
    }

    @Test
    void nullOwnShipReturnsSnapshotIncomplete() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(null).targets(List.of()).build();
        AgentSnapshot snapshot = new AgentSnapshot(1L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("SNAPSHOT_INCOMPLETE");
    }

    @Test
    void nullTargetListTreatedAsEmpty() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(null).build();
        AgentSnapshot snapshot = new AgentSnapshot(1L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("OK");
        assertThat(result.payload().get("target_count").asInt()).isEqualTo(0);
    }

    @Test
    void mixedRiskLevelsReturnsCorrectCountsAndHighestLevel() {
        List<LlmRiskTargetContext> targets = List.of(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3),
                target("t2", RiskLevel.WARNING, 70.0, true, 200.0, 0.5),
                target("t3", RiskLevel.WARNING, 60.0, false, 300.0, 0.8),
                target("t4", RiskLevel.CAUTION, 30.0, false, 0.0, 1.2),
                target("t5", RiskLevel.SAFE, 5.0, false, 0.0, 2.0)
        );
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(targets).build();
        AgentSnapshot snapshot = new AgentSnapshot(10L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);

        ObjectNode p = result.payload();
        assertThat(p.get("highest_risk_level").asText()).isEqualTo("ALARM");
        assertThat(p.get("target_count").asInt()).isEqualTo(5);
        assertThat(p.get("approaching_target_count").asInt()).isEqualTo(2);
        assertThat(p.at("/risk_level_counts/ALARM").asInt()).isEqualTo(1);
        assertThat(p.at("/risk_level_counts/WARNING").asInt()).isEqualTo(2);
        assertThat(p.at("/risk_level_counts/CAUTION").asInt()).isEqualTo(1);
        assertThat(p.at("/risk_level_counts/SAFE").asInt()).isEqualTo(1);
    }

    @Test
    void topRiskTargetIdsReturnAtMostThreeSortedByRisk() {
        List<LlmRiskTargetContext> targets = List.of(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3),
                target("t2", RiskLevel.WARNING, 70.0, true, 200.0, 0.5),
                target("t3", RiskLevel.CAUTION, 40.0, false, 0.0, 1.0),
                target("t4", RiskLevel.SAFE, 5.0, false, 0.0, 2.0)
        );
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(targets).build();

        ToolResult result = tool.execute(noArgCall(), new AgentSnapshot(1L, ctx, Map.of()));

        assertThat(result.payload().get("top_risk_target_ids"))
                .extracting(n -> n.asText())
                .containsExactly("t1", "t2", "t3");
    }

    @Test
    void ownShipFieldsArePresent() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(List.of()).build();

        ToolResult result = tool.execute(noArgCall(), new AgentSnapshot(1L, ctx, Map.of()));

        ObjectNode ownShipNode = (ObjectNode) result.payload().get("own_ship");
        assertThat(ownShipNode.get("id").asText()).isEqualTo("own-ship");
        assertThat(ownShipNode.get("sog_kn").asDouble()).isEqualTo(10.4);
        assertThat(ownShipNode.get("cog_deg").asDouble()).isEqualTo(83.1);
        assertThat(ownShipNode.get("heading_deg").asDouble()).isEqualTo(82.0);
    }

    @Test
    void payloadMutationDoesNotAffectSnapshot() {
        LlmRiskContext ctx = LlmRiskContext.builder().ownShip(ownShip()).targets(List.of(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3)
        )).build();
        AgentSnapshot snapshot = new AgentSnapshot(1L, ctx, Map.of());

        ToolResult result = tool.execute(noArgCall(), snapshot);
        result.payload().put("status", "MUTATED");

        assertThat(snapshot.riskContext().getTargets()).hasSize(1);
    }
}
