package com.whut.map.map_service.llm.agent.advisory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.AdvisoryActionType;
import com.whut.map.map_service.shared.dto.sse.AdvisoryUrgency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryOutputParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AdvisoryOutputParser parser = new AdvisoryOutputParser(MAPPER);

    private AgentSnapshot snapshotWithTargets(String... ids) {
        List<LlmRiskTargetContext> targets = java.util.Arrays.stream(ids)
                .map(id -> LlmRiskTargetContext.builder()
                        .targetId(id).riskLevel(RiskLevel.ALARM).dcpaNm(0.1).tcpaSec(100)
                        .longitude(114.0).latitude(30.0).speedKn(8.0).courseDeg(90.0).build())
                .toList();
        return new AgentSnapshot(1L, LlmRiskContext.builder().targets(targets).build(), Map.of());
    }

    private String validJson(String targetId) {
        return """
                {
                  "summary": "场景存在紧急风险",
                  "affected_targets": ["%s"],
                  "recommended_action": {
                    "type": "COURSE_CHANGE",
                    "description": "建议立即右转",
                    "urgency": "IMMEDIATE"
                  },
                  "evidence_items": ["目标 DCPA 0.1 nm"]
                }
                """.formatted(targetId);
    }

    @Test
    void validJsonMapsToAllFields() {
        AgentSnapshot snapshot = snapshotWithTargets("t1");
        AdvisoryOutputParser.ParsedAdvisory result = parser.parse(validJson("t1"), snapshot);

        assertThat(result).isNotNull();
        assertThat(result.summary()).isEqualTo("场景存在紧急风险");
        assertThat(result.affectedTargets()).containsExactly("t1");
        assertThat(result.recommendedAction().getType()).isEqualTo(AdvisoryActionType.COURSE_CHANGE);
        assertThat(result.recommendedAction().getUrgency()).isEqualTo(AdvisoryUrgency.IMMEDIATE);
        assertThat(result.recommendedAction().getDescription()).isEqualTo("建议立即右转");
        assertThat(result.evidenceItems()).containsExactly("目标 DCPA 0.1 nm");
    }

    @Test
    void missingSummaryReturnsNull() {
        String json = """
                {
                  "affected_targets": ["t1"],
                  "recommended_action": {"type": "MONITOR", "description": "监控", "urgency": "LOW"},
                  "evidence_items": ["fact"]
                }
                """;
        assertThat(parser.parse(json, snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void emptyAffectedTargetsReturnsNull() {
        String json = """
                {
                  "summary": "text",
                  "affected_targets": [],
                  "recommended_action": {"type": "MONITOR", "description": "监控", "urgency": "LOW"},
                  "evidence_items": ["fact"]
                }
                """;
        assertThat(parser.parse(json, snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void emptyEvidenceItemsReturnsNull() {
        String json = """
                {
                  "summary": "text",
                  "affected_targets": ["t1"],
                  "recommended_action": {"type": "MONITOR", "description": "监控", "urgency": "LOW"},
                  "evidence_items": []
                }
                """;
        assertThat(parser.parse(json, snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void unknownTargetIdReturnsNull() {
        assertThat(parser.parse(validJson("unknown-target"), snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void missingRecommendedActionDescriptionReturnsNull() {
        String json = """
                {
                  "summary": "text",
                  "affected_targets": ["t1"],
                  "recommended_action": {"type": "MONITOR", "urgency": "LOW"},
                  "evidence_items": ["fact"]
                }
                """;
        assertThat(parser.parse(json, snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void jsonWrappedInCodeBlockIsParsed() {
        String withBlock = "```json\n" + validJson("t1").trim() + "\n```";
        assertThat(parser.parse(withBlock, snapshotWithTargets("t1"))).isNotNull();
    }

    @Test
    void unknownActionTypeDefaultsToUnknown() {
        String json = """
                {
                  "summary": "text",
                  "affected_targets": ["t1"],
                  "recommended_action": {"type": "FLIP_SHIP", "description": "描述", "urgency": "LOW"},
                  "evidence_items": ["fact"]
                }
                """;
        AdvisoryOutputParser.ParsedAdvisory result = parser.parse(json, snapshotWithTargets("t1"));
        assertThat(result).isNotNull();
        assertThat(result.recommendedAction().getType()).isEqualTo(AdvisoryActionType.UNKNOWN);
    }

    @Test
    void riskLevelFieldIgnoredDoesNotCauseFailure() {
        String json = """
                {
                  "summary": "text",
                  "risk_level": "ALARM",
                  "affected_targets": ["t1"],
                  "recommended_action": {"type": "MONITOR", "description": "监控", "urgency": "LOW"},
                  "evidence_items": ["fact"]
                }
                """;
        assertThat(parser.parse(json, snapshotWithTargets("t1"))).isNotNull();
    }

    @Test
    void nullTextReturnsNull() {
        assertThat(parser.parse(null, snapshotWithTargets("t1"))).isNull();
    }

    @Test
    void invalidJsonReturnsNull() {
        assertThat(parser.parse("not json at all", snapshotWithTargets("t1"))).isNull();
    }
}
