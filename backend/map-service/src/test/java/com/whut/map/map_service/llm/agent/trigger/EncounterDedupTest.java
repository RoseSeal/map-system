package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class EncounterDedupTest {

    private final MutableClock time = new MutableClock();
    private final LlmProperties props = new LlmProperties();
    private LlmRiskContext current;
    private int calls;
    private Consumer<Set<String>> completion;

    private final AgentSnapshotFactory factory = new AgentSnapshotFactory(null, null, null, null) {
        @Override
        public AgentSnapshot build() {
            return new AgentSnapshot(1L, current, Map.of());
        }
    };

    @Test
    void continuousDangerAndOscillationDoNotRetriggerButUpgradeDoes() {
        SceneRiskStateTracker tracker = tracker();

        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(1);

        update(tracker, target("a", RiskLevel.ALARM));
        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.WARNING));
        update(tracker, target("a", RiskLevel.ALARM));
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void thirtySecondsSafeResetsButBriefSafeDoesNot() {
        SceneRiskStateTracker tracker = tracker();

        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.SAFE));
        time.seconds = 29;
        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(1);

        update(tracker, target("a", RiskLevel.SAFE));
        time.seconds = 59;
        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void failureBackoffIncreasesAndCapsAt120Seconds() {
        SceneRiskStateTracker tracker = tracker();
        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of());

        // Retries are due at +30, +60, +120 and then capped at +120 seconds.
        for (long deadline : new long[]{30, 90, 210, 330}) {
            int before = calls;
            time.seconds = deadline - 1;
            update(tracker, target("a", RiskLevel.WARNING));
            assertThat(calls).isEqualTo(before);

            time.seconds = deadline;
            update(tracker, target("a", RiskLevel.WARNING));
            assertThat(calls).isEqualTo(before + 1);
            completion.accept(Set.of());
        }
    }

    @Test
    void upgradeBypassesBackoffAndNewTargetsWaitForCompletion() {
        SceneRiskStateTracker tracker = tracker();
        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of());

        update(tracker, target("a", RiskLevel.ALARM));
        assertThat(calls).isEqualTo(2);

        update(tracker, target("a", RiskLevel.ALARM), target("b", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(2);

        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.ALARM), target("b", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(3);
    }

    @Test
    void onlyCoveredTargetsConsumeSuccessAndOthersBackOff() {
        SceneRiskStateTracker tracker = tracker();
        update(tracker, target("a", RiskLevel.WARNING), target("b", RiskLevel.WARNING));
        completion.accept(Set.of("a"));

        update(tracker, target("a", RiskLevel.WARNING), target("b", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(1);

        time.seconds = 30;
        update(tracker, target("a", RiskLevel.WARNING), target("b", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(2);

        completion.accept(Set.of("b"));
        time.seconds = 100;
        update(tracker, target("a", RiskLevel.WARNING), target("b", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void absentTargetIsEvictedAndOldCallbackCannotCoverNewEncounter() {
        SceneRiskStateTracker tracker = tracker();
        update(tracker, target("a", RiskLevel.WARNING));
        Consumer<Set<String>> oldCompletion = completion;

        update(tracker);
        update(tracker, target("a", RiskLevel.WARNING));
        oldCompletion.accept(Set.of("a"));

        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(calls).isEqualTo(2);
    }

    @Test
    void upgradeDuringGenerationRemainsPending() {
        SceneRiskStateTracker tracker = tracker();
        update(tracker, target("a", RiskLevel.WARNING));
        update(tracker, target("a", RiskLevel.ALARM));

        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.ALARM));

        assertThat(calls).isEqualTo(2);
    }

    @Test
    void dangerUsesWarningOrStrictApproachingTcpaThreshold() {
        SceneRiskStateTracker tracker = tracker();
        LlmRiskTargetContext caution = target("a", RiskLevel.CAUTION);

        caution.setTcpaSec(300);
        update(tracker, caution);
        assertThat(calls).isZero();

        caution.setTcpaSec(299);
        caution.setApproaching(false);
        update(tracker, caution);
        assertThat(calls).isZero();

        caution.setApproaching(true);
        update(tracker, caution);
        assertThat(calls).isEqualTo(1);
    }

    @Test
    void defaultEnablesDedupAndSnapshotFailureBacksOff() {
        assertThat(props.getAdvisory().isEncounterDedupEnabled()).isTrue();
        props.getAdvisory().setEnabled(true);
        AtomicInteger attempts = new AtomicInteger();
        AgentSnapshotFactory brokenFactory = new AgentSnapshotFactory(null, null, null, null) {
            @Override
            public AgentSnapshot build() {
                attempts.incrementAndGet();
                throw new IllegalStateException("test");
            }
        };
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(props, brokenFactory,
                new StaticListableBeanFactory().getBeanProvider(AdvisoryTriggerPort.class), time);

        update(tracker, target("a", RiskLevel.WARNING));
        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(attempts.get()).isEqualTo(1);

        time.seconds = 30;
        update(tracker, target("a", RiskLevel.WARNING));
        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    void disabledSwitchPreservesImmediateRetry() {
        SceneRiskStateTracker tracker = tracker();
        props.getAdvisory().setEncounterDedupEnabled(false);

        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of());
        update(tracker, target("a", RiskLevel.WARNING));
        completion.accept(Set.of("a"));
        update(tracker, target("a", RiskLevel.WARNING));

        assertThat(calls).isEqualTo(3);
    }

    // ---- helpers ----

    private SceneRiskStateTracker tracker() {
        props.getAdvisory().setEnabled(true);
        AdvisoryTriggerPort port = (snapshot, onComplete) -> {
            calls++;
            completion = onComplete;
        };
        return new SceneRiskStateTracker(props, factory,
                new StaticListableBeanFactory(Map.of("port", port)).getBeanProvider(AdvisoryTriggerPort.class),
                time);
    }

    private static LlmRiskTargetContext target(String id, RiskLevel risk) {
        return LlmRiskTargetContext.builder()
                .targetId(id)
                .riskLevel(risk)
                .approaching(risk != RiskLevel.SAFE)
                .tcpaSec(risk == RiskLevel.SAFE ? 400 : 200)
                .build();
    }

    private void update(SceneRiskStateTracker tracker, LlmRiskTargetContext... targets) {
        current = LlmRiskContext.builder().targets(List.of(targets)).build();
        tracker.onSceneUpdate(current);
    }

    private static final class MutableClock extends Clock {
        long seconds;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochSecond(seconds);
        }
    }
}
