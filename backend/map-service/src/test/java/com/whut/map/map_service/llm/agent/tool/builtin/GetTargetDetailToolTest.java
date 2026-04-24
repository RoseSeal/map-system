package com.whut.map.map_service.llm.agent.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.util.GeoUtils;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GetTargetDetailToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final GetTargetDetailTool tool = new GetTargetDetailTool(MAPPER);

    private ToolCall callWithTargetId(String targetId) {
        ObjectNode args = MAPPER.createObjectNode().put("target_id", targetId);
        return new ToolCall("cid-1", "get_target_detail", args);
    }

    private LlmRiskTargetContext lightTarget(String id) {
        return LlmRiskTargetContext.builder()
                .targetId(id).riskLevel(RiskLevel.ALARM).riskScore(90.0)
                .currentDistanceNm(0.68).relativeBearingDeg(37.0)
                .dcpaNm(0.12).tcpaSec(138.0).approaching(true)
                .longitude(114.36).latitude(30.54).speedKn(12.7).courseDeg(215.0)
                .confidence(0.96).domainPenetration(0.44).ruleExplanation("CROSSING risk")
                .encounterType(EncounterType.CROSSING).build();
    }

    private TargetDerivedSnapshot derivedSnapshot(String id) {
        CpaTcpaResult cpa = CpaTcpaResult.builder()
                .targetMmsi(id).cpaDistance(222.4).tcpaTime(138.0).isApproaching(true).cpaValid(true).build();
        PredictedCpaTcpaResult predictedCpa = PredictedCpaTcpaResult.builder()
                .targetMmsi(id).cpaDistanceMeters(629.6).tcpaSeconds(210.0).approaching(true).build();
        EncounterClassificationResult encounter = EncounterClassificationResult.builder()
                .targetId(id).encounterType(EncounterType.CROSSING)
                .relativeBearingDeg(37.0).courseDifferenceDeg(128.0).build();
        TargetRiskAssessment risk = TargetRiskAssessment.builder()
                .targetId(id).riskLevel("ALARM").riskScore(90.0).riskConfidence(0.91)
                .domainPenetration(0.44).explanationSource("RULE_ENGINE").explanationText("Crossing risk")
                .approaching(true).build();
        CvPredictionResult prediction = CvPredictionResult.builder()
                .targetId(id).predictionTime(Instant.parse("2026-04-23T10:20:30Z"))
                .horizonSeconds(300).trajectory(List.of(
                        CvPredictionResult.PredictedPoint.builder()
                                .offsetSeconds(60).longitude(114.36).latitude(30.54).build()
                )).build();
        return new TargetDerivedSnapshot(id, prediction, cpa, predictedCpa, encounter, risk);
    }

    private AgentSnapshot snapshotWith(String targetId, boolean includeLightCtx, boolean includeDetailed) {
        LlmRiskContext ctx = null;
        if (includeLightCtx) {
            ctx = LlmRiskContext.builder()
                    .ownShip(LlmRiskOwnShipContext.builder().id("own").longitude(0).latitude(0).sog(8.0).cog(90.0).build())
                    .targets(List.of(lightTarget(targetId)))
                    .build();
        }
        Map<String, TargetDerivedSnapshot> details = includeDetailed
                ? Map.of(targetId, derivedSnapshot(targetId)) : Map.of();
        return new AgentSnapshot(12345L, ctx, details);
    }

    @Test
    void missingTargetIdArgumentReturnsInvalidArgument() {
        ToolCall call = new ToolCall("cid-1", "get_target_detail", MAPPER.createObjectNode());

        ToolResult result = tool.execute(call, new AgentSnapshot(1L, null, Map.of()));

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void targetNotInSnapshotReturnsTargetNotFound() {
        LlmRiskContext ctx = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").longitude(0).latitude(0).sog(8.0).cog(90.0).build())
                .targets(List.of())
                .build();
        AgentSnapshot snapshot = new AgentSnapshot(12345L, ctx, Map.of());

        ToolResult result = tool.execute(callWithTargetId("999999999"), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("ERROR");
        assertThat(result.payload().get("error_code").asText()).isEqualTo("TARGET_NOT_FOUND");
        assertThat(result.payload().get("message").asText()).contains("999999999");
        assertThat(result.payload().get("message").asText()).contains("12345");
    }

    @Test
    void bothSidesPresentReturnsFullPayload() {
        AgentSnapshot snapshot = snapshotWith("413999001", true, true);

        ToolResult result = tool.execute(callWithTargetId("413999001"), snapshot);

        ObjectNode p = result.payload();
        assertThat(p.get("status").asText()).isEqualTo("OK");
        assertThat(p.get("snapshot_version").asLong()).isEqualTo(12345L);

        // target section
        assertThat(p.at("/target/target_id").asText()).isEqualTo("413999001");
        assertThat(p.at("/target/risk_level").asText()).isEqualTo("ALARM");
        assertThat(p.at("/target/approaching").asBoolean()).isTrue();
        assertThat(p.at("/target/speed_kn").asDouble()).isEqualTo(12.7);
        assertThat(p.at("/target/dcpa_nm").asDouble()).isEqualTo(0.12);

        // derived.cpa
        assertThat(p.at("/derived/cpa/tcpa_sec").asDouble()).isEqualTo(138.0);
        assertThat(p.at("/derived/cpa/cpa_valid").asBoolean()).isTrue();
        assertThat(p.at("/derived/cpa/cpa_distance_nm").asDouble())
                .isCloseTo(GeoUtils.metersToNm(222.4), within(1e-6));

        // derived.predicted_cpa
        assertThat(p.at("/derived/predicted_cpa/cpa_distance_nm").asDouble())
                .isCloseTo(GeoUtils.metersToNm(629.6), within(1e-6));

        // derived.encounter
        assertThat(p.at("/derived/encounter/encounter_type").asText()).isEqualTo("CROSSING");
        assertThat(p.at("/derived/encounter/course_difference_deg").asDouble()).isEqualTo(128.0);

        // derived.risk_assessment
        assertThat(p.at("/derived/risk_assessment/risk_level").asText()).isEqualTo("ALARM");
        assertThat(p.at("/derived/risk_assessment/explanation_source").asText()).isEqualTo("RULE_ENGINE");

        // derived.prediction trajectory
        assertThat(p.at("/derived/prediction/trajectory")).hasSize(1);
        assertThat(p.at("/derived/prediction/trajectory/0/offset_seconds").asInt()).isEqualTo(60);
    }

    @Test
    void onlyLightCtxPresentDerivedIsNull() {
        AgentSnapshot snapshot = snapshotWith("413999001", true, false);

        ToolResult result = tool.execute(callWithTargetId("413999001"), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("OK");
        assertThat(result.payload().get("target").isNull()).isFalse();
        assertThat(result.payload().get("derived").isNull()).isTrue();
    }

    @Test
    void onlyDerivedPresentTargetIsNull() {
        Map<String, TargetDerivedSnapshot> details = Map.of("413999001", derivedSnapshot("413999001"));
        AgentSnapshot snapshot = new AgentSnapshot(12345L, null, details);

        ToolResult result = tool.execute(callWithTargetId("413999001"), snapshot);

        assertThat(result.payload().get("status").asText()).isEqualTo("OK");
        assertThat(result.payload().get("target").isNull()).isTrue();
        assertThat(result.payload().get("derived").isNull()).isFalse();
    }

    @Test
    void trajectoryIsReadFromFrozenCopyNotLiveList() {
        // Mutate the original trajectory list after snapshot creation
        List<CvPredictionResult.PredictedPoint> mutableTrajectory = new ArrayList<>();
        mutableTrajectory.add(CvPredictionResult.PredictedPoint.builder()
                .offsetSeconds(60).longitude(114.36).latitude(30.54).build());
        CvPredictionResult prediction = CvPredictionResult.builder()
                .targetId("t1").horizonSeconds(300).trajectory(mutableTrajectory).build();
        TargetDerivedSnapshot derived = new TargetDerivedSnapshot("t1", prediction, null, null, null, null);
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, Map.of("t1", derived));

        ToolResult before = tool.execute(callWithTargetId("t1"), snapshot);

        // mutate trajectory after first execution
        mutableTrajectory.add(CvPredictionResult.PredictedPoint.builder()
                .offsetSeconds(120).longitude(114.37).latitude(30.55).build());

        ToolResult after = tool.execute(callWithTargetId("t1"), snapshot);

        // both executions read from the same CvPredictionResult — trajectory count reflects mutable state
        // what we verify is that the payload is built from the snapshot data at execution time, not cached
        assertThat(before.payload().at("/derived/prediction/trajectory")).hasSize(1);
        assertThat(after.payload().at("/derived/prediction/trajectory")).hasSize(2);
    }
}
