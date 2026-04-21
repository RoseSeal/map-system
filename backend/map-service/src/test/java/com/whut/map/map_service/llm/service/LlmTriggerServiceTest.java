package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LlmTriggerServiceTest {

    @Test
    void safeTargetIsSkipped() {
        LlmProperties props = enabledProps(3600);
        List<String> triggered = new ArrayList<>();
        LlmTriggerService service = new LlmTriggerService(props, immediateService(null));

        service.triggerExplanationsIfNeeded(context(RiskLevel.SAFE), e -> triggered.add(e.getTargetId()), (t, e) -> {});

        assertThat(triggered).isEmpty();
    }

    @Test
    void levelUpgradeBypassesCooldownGate() {
        LlmProperties props = enabledProps(3600);
        AtomicInteger callCount = new AtomicInteger();

        // Service echoes the triggered target's actual risk level in the explanation,
        // so lastExplainedLevelMap is updated to the correct level after each trigger.
        LlmTriggerService service = new LlmTriggerService(props, levelEchoService());

        // First call: CAUTION, no cooldown entry → triggers via cooldown gate,
        // wrappedOnExplanation updates lastExplained[t1] = CAUTION
        service.triggerExplanationsIfNeeded(context(RiskLevel.CAUTION), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(1);

        // Second call: WARNING immediately (cooldown of 3600s still active)
        // isLevelUpgrade(WARNING > CAUTION) = true → upgrade gate fires regardless of cooldown
        service.triggerExplanationsIfNeeded(context(RiskLevel.WARNING), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void sameLevelWithActiveCooldownDoesNotTrigger() {
        LlmProperties props = enabledProps(3600);
        AtomicInteger callCount = new AtomicInteger();
        LlmTriggerService service = new LlmTriggerService(props, immediateService(explanation("t1", "WARNING")));

        // First call triggers and sets cooldown
        service.triggerExplanationsIfNeeded(context(RiskLevel.WARNING), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(1);

        // Second call immediately: lastExplained=WARNING, isLevelUpgrade(WARNING>WARNING)=false, cooldown active
        service.triggerExplanationsIfNeeded(context(RiskLevel.WARNING), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void sameLevelWithExpiredCooldownTriggers() {
        LlmProperties props = enabledProps(0); // zero cooldown = always expired
        AtomicInteger callCount = new AtomicInteger();
        LlmTriggerService service = new LlmTriggerService(props, immediateService(explanation("t1", "WARNING")));

        service.triggerExplanationsIfNeeded(context(RiskLevel.WARNING), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(1);

        service.triggerExplanationsIfNeeded(context(RiskLevel.WARNING), e -> callCount.incrementAndGet(), (t, e) -> {});
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void lastExplainedLevelMapUpdatedAfterExplanationReturns() {
        LlmProperties props = enabledProps(3600);
        LlmTriggerService service = new LlmTriggerService(props, immediateService(explanation("t1", "CAUTION")));
        AtomicInteger secondCallCount = new AtomicInteger();

        // First call: fires, callback updates lastExplainedLevelMap to CAUTION
        service.triggerExplanationsIfNeeded(context(RiskLevel.CAUTION), e -> {}, (t, e) -> {});

        // Same level, cooldown still active → no trigger
        service.triggerExplanationsIfNeeded(context(RiskLevel.CAUTION), e -> secondCallCount.incrementAndGet(), (t, e) -> {});
        assertThat(secondCallCount.get()).isEqualTo(0);
    }

    // ---- helpers ----

    private static LlmProperties enabledProps(int cooldownSeconds) {
        LlmProperties p = new LlmProperties();
        p.setEnabled(true);
        p.setCooldownSeconds(cooldownSeconds);
        p.setMaxTargetsPerCall(1);
        return p;
    }

    private static LlmRiskContext context(RiskLevel level) {
        return LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").sog(10).cog(90).build())
                .targets(List.of(LlmRiskTargetContext.builder()
                        .targetId("t1")
                        .riskLevel(level)
                        .tcpaSec(200.0)
                        .build()))
                .build();
    }

    private static LlmExplanation explanation(String targetId, String riskLevel) {
        return LlmExplanation.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .text("explanation text")
                .build();
    }

    // Returns an explanation whose riskLevel matches the triggered target's level,
    // so lastExplainedLevelMap is updated correctly for upgrade-gate tests.
    private static LlmExplanationService levelEchoService() {
        return new LlmExplanationService(null, null, null, null) {
            @Override
            public void generateTargetExplanationsAsync(
                    LlmRiskOwnShipContext ownShip,
                    List<ExplanationTrigger> triggers,
                    Consumer<LlmExplanation> onSuccess,
                    BiConsumer<LlmRiskTargetContext, LlmExplanationError> onError
            ) {
                triggers.forEach(trigger -> onSuccess.accept(LlmExplanation.builder()
                        .targetId(trigger.target().getTargetId())
                        .riskLevel(trigger.target().getRiskLevel().name())
                        .text("echo")
                        .build()));
            }
        };
    }

    private static LlmExplanationService immediateService(LlmExplanation toReturn) {
        return new LlmExplanationService(null, null, null, null) {
            @Override
            public void generateTargetExplanationsAsync(
                    LlmRiskOwnShipContext ownShip,
                    List<ExplanationTrigger> triggers,
                    Consumer<LlmExplanation> onSuccess,
                    BiConsumer<LlmRiskTargetContext, LlmExplanationError> onError
            ) {
                if (toReturn != null) {
                    triggers.forEach(trigger -> onSuccess.accept(toReturn));
                }
            }
        };
    }
}
