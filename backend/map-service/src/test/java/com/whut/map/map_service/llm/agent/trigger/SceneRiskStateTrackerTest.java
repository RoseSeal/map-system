package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SceneRiskStateTrackerTest {

    @Test
    void advisoryDisabledSkipsAllTriggers() {
        LlmProperties props = propsWithAdvisory(false, 300);
        AtomicInteger portCalls = new AtomicInteger();
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(
                props, stubFactory(), providerOf(immediatePort(portCalls)));

        tracker.onSceneUpdate(context(RiskLevel.ALARM, 100.0, true));

        assertThat(portCalls.get()).isZero();
    }

    @Test
    void levelUpgradeTriggersWhenGeneratingFlagIsFalse() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(
                props, stubFactory(), providerOf(immediatePort(portCalls)));

        tracker.onSceneUpdate(context(RiskLevel.ALARM, 400.0, true));

        assertThat(portCalls.get()).isEqualTo(1);
    }

    @Test
    void flagBlocksConcurrentAdvisoryUntilOnCompleteIsCalled() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();
        AtomicReference<Runnable> capturedCompletion = new AtomicReference<>();

        // Async-style port: captures onComplete but does not call it immediately
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(props, stubFactory(),
                providerOf((snapshot, onComplete) -> {
                    portCalls.incrementAndGet();
                    capturedCompletion.set(onComplete);
                }));

        // First trigger: SAFE→ALARM upgrade
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 400.0, true));
        assertThat(portCalls.get()).isEqualTo(1);

        // Flag is still true (onComplete not called): same conditions skipped
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 400.0, true));
        assertThat(portCalls.get()).isEqualTo(1);

        // Advisory finishes: onComplete clears the flag
        capturedCompletion.get().run();

        // Now TCPA crosses threshold (200 < 300) → triggers again
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 200.0, true));
        assertThat(portCalls.get()).isEqualTo(2);
    }

    @Test
    void clearGeneratingFlagManuallyAlsoResetsFlag() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();

        // Async-style port: flag stays true until manually cleared
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(props, stubFactory(),
                providerOf((snapshot, onComplete) -> portCalls.incrementAndGet()));

        tracker.onSceneUpdate(context(RiskLevel.ALARM, 400.0, true));
        assertThat(portCalls.get()).isEqualTo(1);

        // Flag still true (onComplete never called); manual reset simulates Step 3 error path
        tracker.clearGeneratingFlag();

        // TCPA crosses threshold → triggers
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 200.0, true));
        assertThat(portCalls.get()).isEqualTo(2);
    }

    @Test
    void tcpaCrossedTriggers() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(
                props, stubFactory(), providerOf(immediatePort(portCalls)));

        // CAUTION level upgrade (SAFE→CAUTION) + tcpa < 300 → triggers
        tracker.onSceneUpdate(context(RiskLevel.CAUTION, 200.0, true));
        assertThat(portCalls.get()).isEqualTo(1);
    }

    @Test
    void reEscalationAfterDeescalationIsDetected() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(
                props, stubFactory(), providerOf(immediatePort(portCalls)));

        // First escalation: SAFE→WARNING
        tracker.onSceneUpdate(context(RiskLevel.WARNING, 400.0, false));
        assertThat(portCalls.get()).isEqualTo(1);

        // De-escalation: scene drops back to SAFE
        tracker.onSceneUpdate(context(RiskLevel.SAFE, 400.0, false));

        // Re-escalation: new incident rises to WARNING again
        tracker.onSceneUpdate(context(RiskLevel.WARNING, 400.0, false));
        assertThat(portCalls.get()).isEqualTo(2);
    }

    @Test
    void snapshotFactoryExceptionResetsGeneratingFlag() {
        LlmProperties props = propsWithAdvisory(true, 300);
        AtomicInteger portCalls = new AtomicInteger();
        AtomicInteger factoryAttempts = new AtomicInteger();
        AgentSnapshotFactory throwingFactory = new AgentSnapshotFactory(null, null, null, null) {
            @Override
            public AgentSnapshot build() {
                factoryAttempts.incrementAndGet();
                throw new IllegalStateException("not initialized");
            }
        };
        SceneRiskStateTracker tracker = new SceneRiskStateTracker(
                props, throwingFactory, providerOf(immediatePort(portCalls)));

        // First call: factory throws, generatingFlag reset by exception handler
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 400.0, true));
        assertThat(portCalls.get()).isZero();
        assertThat(factoryAttempts.get()).isEqualTo(1);

        // Second call: TCPA crosses threshold (200 < 300) → flag was reset, factory is called again
        tracker.onSceneUpdate(context(RiskLevel.ALARM, 200.0, true));
        assertThat(portCalls.get()).isZero();
        assertThat(factoryAttempts.get()).isEqualTo(2);
    }

    // ---- helpers ----

    private static LlmProperties propsWithAdvisory(boolean enabled, int tcpaThreshold) {
        LlmProperties p = new LlmProperties();
        p.getAdvisory().setEnabled(enabled);
        p.getAdvisory().setTcpaThresholdSeconds(tcpaThreshold);
        return p;
    }

    private static LlmRiskContext context(RiskLevel level, double tcpaSec, boolean approaching) {
        return LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").sog(10).cog(90).build())
                .targets(List.of(LlmRiskTargetContext.builder()
                        .targetId("t1")
                        .riskLevel(level)
                        .tcpaSec(tcpaSec)
                        .approaching(approaching)
                        .build()))
                .build();
    }

    private static AgentSnapshotFactory stubFactory() {
        return new AgentSnapshotFactory(null, null, null, null) {
            @Override
            public AgentSnapshot build() {
                return new AgentSnapshot(1L, null, Map.of());
            }
        };
    }

    /** Port that calls onComplete synchronously (models no-op or fast-path). */
    private static AdvisoryTriggerPort immediatePort(AtomicInteger counter) {
        return (snapshot, onComplete) -> {
            counter.incrementAndGet();
            onComplete.run();
        };
    }

    private static ObjectProvider<AdvisoryTriggerPort> providerOf(AdvisoryTriggerPort port) {
        return new StaticListableBeanFactory(Map.of(AdvisoryTriggerPort.class.getName(), port))
                .getBeanProvider(AdvisoryTriggerPort.class);
    }
}
