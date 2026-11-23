package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.AdvisoryActionType;
import com.whut.map.map_service.shared.dto.sse.AdvisoryUrgency;
import com.whut.map.map_service.shared.dto.sse.RecommendedAction;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdvisoryCogValidatorTest {

    private AgentSnapshot snapshot(Double cog) {
        LlmRiskTargetContext target = LlmRiskTargetContext.builder()
                .targetId("t1").riskLevel(RiskLevel.ALARM).dcpaNm(0.1).tcpaSec(100)
                .longitude(114.0).latitude(30.0).speedKn(8.0).courseDeg(90.0).build();
        LlmRiskOwnShipContext ownShip = cog == null
                ? null
                : LlmRiskOwnShipContext.builder().id("own-1").cog(cog).build();
        return new AgentSnapshot(
                1L,
                LlmRiskContext.builder().targets(List.of(target)).ownShip(ownShip).build(),
                Map.of()
        );
    }

    private AdvisoryOutputParser.ParsedAdvisory parsed(String summary, String description, List<String> evidence) {
        RecommendedAction action = RecommendedAction.builder()
                .type(AdvisoryActionType.MONITOR)
                .description(description)
                .urgency(AdvisoryUrgency.LOW)
                .build();
        return new AdvisoryOutputParser.ParsedAdvisory(summary, List.of("t1"), action, evidence);
    }

    @Test
    void claimInActionDescriptionIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("场景摘要", "将航向从296度改为341度", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("recommended_action.description");
        assertThat(result.claimedCog()).isEqualTo(296.0);
        assertThat(result.referenceCog()).isEqualTo(29.6);
    }

    @Test
    void claimInSummaryIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船当前对地航向 296 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("summary");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void claimInEvidenceIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("场景摘要", "保持航向", List.of("本船当前对地航向为 296°"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("evidence_items[0]");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void suggestedAndTargetHeadingsAreIgnored() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "目标船当前航向 341 度，建议将本船航向转至 341 度",
                        "建议右转至 341 度",
                        List.of("目标船对地航向 341 度", "目标船船首向 296 度")
                )
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void fromCorrectCurrentValueToDifferentCourseIsAccepted() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("场景摘要", "将航向从29.6度改为341度", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void exactlyOneDegreeDeviationIsAccepted() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(100.0),
                parsed("本船当前对地航向 101 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void wraparoundWithinOneDegreeIsAccepted() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(359.6),
                parsed("本船当前对地航向 0.5 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void wraparoundBeyondOneDegreeIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(359.0),
                parsed("本船当前对地航向 0.5 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.claimedCog()).isEqualTo(0.5);
        assertThat(AdvisoryCogValidator.circularDifferenceDeg(0.5, 359.0)).isEqualTo(1.5);
    }

    @Test
    void anyMismatchAmongMultipleValuesIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "本船当前对地航向 29.6 度",
                        "保持航向",
                        List.of("DCPA 0.1 nm", "本船当前对地航向 296 度")
                )
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("evidence_items[1]");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void claimWithoutReferenceCogIsRejectedWithReadableReason() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(null),
                parsed("本船当前对地航向 29.6 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("summary");
        assertThat(result.claimedCog()).isEqualTo(29.6);
        assertThat(result.referenceCog()).isNull();
        assertThat(result.reason()).contains("missing or invalid");
    }

    @Test
    void englishOwnShipCogFormIsChecked() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("own_ship_state.cog_deg = 296", "keep course", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("summary");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void targetShipClaimInSameSentenceIsNotAttributedToOwnShip() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("场景摘要", "目标船当前航向 296 度，本船保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void headingAndRelativeTurnAreNotComparedAsCog() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "本船船首向 296 度",
                        "本船相对转角 30 度",
                        List.of("本船 heading 296")
                )
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void plannedOwnShipCourseIsNotTreatedAsCurrent() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "本船建议将航向调整为 341 度",
                        "本船拟转向 341 度",
                        List.of("DCPA 0.1 nm")
                )
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void b1EvidenceSentenceWithOwnShipStatusIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "场景摘要",
                        "保持航向",
                        List.of("本船状态：位置114.29669°E, 30.574965°N，航向296°，航速5.39节")
                )
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("evidence_items[0]");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void b1SourceTaggedEvidenceSentenceIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "场景摘要",
                        "保持航向",
                        List.of("[source: own_ship_state] 本船状态：航向296°，航速5.38节")
                )
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("evidence_items[0]");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void ownShipHeadingWithoutCurrentMarkerIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船航向是296°", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("summary");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void parenthesizedOwnShipHeadingIsRejected() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船状态：航向(296°)", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("summary");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void mixedOwnShipAndTargetShipCoursesDoNotRejectOwnClaim() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船航向29.6°，目标船航向296°", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void suggestedOwnShipCourseIsNotTreatedAsCurrent() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("建议本船航向为341°", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void explicitCurrentMarkerOverridesSuggestedRetainWording() {
        AdvisoryCogValidator.Result keep = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("建议保持本船当前航向296°", "保持航向", List.of("DCPA 0.1 nm"))
        );
        assertThat(keep.accepted()).isFalse();
        assertThat(keep.claimedCog()).isEqualTo(296.0);

        AdvisoryCogValidator.Result maintain = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("建议维持本船当前航向296°", "保持航向", List.of("DCPA 0.1 nm"))
        );
        assertThat(maintain.accepted()).isFalse();
        assertThat(maintain.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void fromCurrentCourseChangeChecksOnlyStartValue() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed(
                        "场景摘要",
                        "建议将本船从当前航向296°调整至341°",
                        List.of("DCPA 0.1 nm")
                )
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.field()).isEqualTo("recommended_action.description");
        assertThat(result.claimedCog()).isEqualTo(296.0);
    }

    @Test
    void fourDigitCurrentClaimIsRejectedInsteadOfMissed() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船当前航向3896°", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.claimedCog()).isEqualTo(3896.0);
        assertThat(result.reason()).contains("outside");
    }

    @Test
    void speedRangeFromToIsNotTreatedAsCourseClaim() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("场景摘要", "将航速从10到20节", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void claimedCourse360IsTreatedAsZero() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(0.0),
                parsed("本船当前对地航向 360 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void referenceCourse360IsInvalid() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(360.0),
                parsed("本船当前对地航向 0 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.referenceCog()).isNull();
        assertThat(result.reason()).contains("missing or invalid");
    }

    @Test
    void outOfRangeClaimIsRejectedInsteadOfWrapped() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(29.6),
                parsed("本船当前对地航向 389.6 度", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.claimedCog()).isEqualTo(389.6);
        assertThat(result.reason()).contains("outside");
    }

    @Test
    void noCogClaimSkipsReferenceLookup() {
        AdvisoryCogValidator.Result result = AdvisoryCogValidator.validate(
                snapshot(null),
                parsed("场景摘要", "保持航向", List.of("DCPA 0.1 nm"))
        );

        assertThat(result.accepted()).isTrue();
    }
}