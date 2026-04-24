package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GetTopRiskTargetsToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GetTopRiskTargetsTool tool = new GetTopRiskTargetsTool(MAPPER);

    private ToolCall callWith(String argsJson) {
        try {
            ObjectNode args = (ObjectNode) MAPPER.readTree(argsJson);
            return new ToolCall("cid-1", "get_top_risk_targets", args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ToolCall noArgCall() {
        return new ToolCall("cid-1", "get_top_risk_targets", MAPPER.createObjectNode());
    }

    private LlmRiskTargetContext target(String id, RiskLevel level, Double score,
                                        boolean approaching, double tcpa, double dist) {
        return LlmRiskTargetContext.builder()
                .targetId(id).riskLevel(level).riskScore(score).approaching(approaching)
                .tcpaSec(tcpa).currentDistanceNm(dist).dcpaNm(0.1)
                .longitude(114.36).latitude(30.55).speedKn(8.0).courseDeg(200.0).build();
    }

    private AgentSnapshot snapshotWith(LlmRiskTargetContext... targets) {
        LlmRiskContext ctx = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").longitude(0).latitude(0).sog(8.0).cog(90.0).build())
                .targets(List.of(targets))
                .build();
        return new AgentSnapshot(1L, ctx, Map.of());
    }

    @Test
    void defaultLimitIsThree() {
        AgentSnapshot snapshot = snapshotWith(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3),
                target("t2", RiskLevel.ALARM, 80.0, true, 200.0, 0.5),
                target("t3", RiskLevel.WARNING, 60.0, false, 0.0, 1.0),
                target("t4", RiskLevel.CAUTION, 30.0, false, 0.0, 2.0)
        );

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("items")).hasSize(3);
    }

    @Test
    void limitParameterIsRespected() {
        AgentSnapshot snapshot = snapshotWith(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3),
                target("t2", RiskLevel.WARNING, 70.0, true, 200.0, 0.5),
                target("t3", RiskLevel.SAFE, 10.0, false, 0.0, 2.0)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":2}"), snapshot);

        assertThat(result.payload().get("items")).hasSize(2);
    }

    @Test
    void limitOutOfRangeReturnsInvalidArgument() {
        ToolResult tooLow = tool.execute(callWith("{\"limit\":0}"), snapshotWith());
        assertThat(tooLow.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");

        ToolResult tooHigh = tool.execute(callWith("{\"limit\":11}"), snapshotWith());
        assertThat(tooHigh.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void nullLimitReturnsInvalidArgument() {
        ToolResult result = tool.execute(callWith("{\"limit\":null}"), snapshotWith());

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void floatLimitReturnsInvalidArgument() {
        ToolResult result = tool.execute(callWith("{\"limit\":1.5}"), snapshotWith());

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void stringLimitReturnsInvalidArgument() {
        ToolResult result = tool.execute(callWith("{\"limit\":\"three\"}"), snapshotWith());

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void invalidMinLevelReturnsInvalidArgument() {
        ToolResult result = tool.execute(callWith("{\"min_level\":\"VERY_HIGH\"}"), snapshotWith());

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void minLevelFiltersOutLowerLevels() {
        AgentSnapshot snapshot = snapshotWith(
                target("t1", RiskLevel.ALARM, 90.0, true, 100.0, 0.3),
                target("t2", RiskLevel.WARNING, 70.0, true, 200.0, 0.5),
                target("t3", RiskLevel.CAUTION, 30.0, false, 0.0, 1.0),
                target("t4", RiskLevel.SAFE, 10.0, false, 0.0, 2.0)
        );

        ToolResult result = tool.execute(callWith("{\"min_level\":\"WARNING\",\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("t1", "t2");
    }

    @Test
    void sortingByRiskLevelDescThenScoreDesc() {
        AgentSnapshot snapshot = snapshotWith(
                target("low_score", RiskLevel.ALARM, 70.0, true, 100.0, 0.5),
                target("high_score", RiskLevel.ALARM, 90.0, true, 100.0, 0.5),
                target("warning", RiskLevel.WARNING, 95.0, true, 100.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":3}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("high_score", "low_score", "warning");
    }

    @Test
    void approachingTargetsSortedBeforeNonApproaching() {
        AgentSnapshot snapshot = snapshotWith(
                target("not_approaching", RiskLevel.ALARM, 90.0, false, 0.0, 0.5),
                target("approaching", RiskLevel.ALARM, 90.0, true, 100.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("approaching", "not_approaching");
    }

    @Test
    void validTcpaSortedBeforeInvalidTcpaWithinApproachingBucket() {
        // tcpaSec <= 0 treated as non-value, sorted after positive TCPA
        AgentSnapshot snapshot = snapshotWith(
                target("zero_tcpa", RiskLevel.ALARM, 90.0, true, 0.0, 0.5),
                target("valid_tcpa", RiskLevel.ALARM, 90.0, true, 100.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("valid_tcpa", "zero_tcpa");
    }

    @Test
    void tcpaAscendingWithinSameBucket() {
        AgentSnapshot snapshot = snapshotWith(
                target("long_tcpa", RiskLevel.ALARM, 90.0, true, 300.0, 0.5),
                target("short_tcpa", RiskLevel.ALARM, 90.0, true, 50.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("short_tcpa", "long_tcpa");
    }

    @Test
    void distanceAscendingWhenTcpaEqual() {
        AgentSnapshot snapshot = snapshotWith(
                target("far", RiskLevel.ALARM, 90.0, true, 100.0, 2.0),
                target("near", RiskLevel.ALARM, 90.0, true, 100.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("near", "far");
    }

    @Test
    void targetIdLexicographicAsLastTieBreaker() {
        AgentSnapshot snapshot = snapshotWith(
                target("z_target", RiskLevel.ALARM, 90.0, true, 100.0, 0.5),
                target("a_target", RiskLevel.ALARM, 90.0, true, 100.0, 0.5)
        );

        ToolResult result = tool.execute(callWith("{\"limit\":10}"), snapshot);

        assertThat(result.payload().get("items"))
                .extracting(n -> n.get("target_id").asText())
                .containsExactly("a_target", "z_target");
    }

    @Test
    void nullRiskContextReturnsSnapshotIncomplete() {
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, null);

        ToolResult result = tool.execute(noArgCall(), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("SNAPSHOT_INCOMPLETE");
    }
}
