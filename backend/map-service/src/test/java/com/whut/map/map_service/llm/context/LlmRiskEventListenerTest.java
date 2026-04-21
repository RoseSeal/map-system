package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.risk.config.EncounterProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.sse.ExplanationPayload;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.llm.agent.trigger.SceneRiskStateTracker;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.service.LlmExplanationService;
import com.whut.map.map_service.llm.service.LlmTriggerService;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRiskEventListenerTest {

    @Test
    void listenerUpdatesRiskContextHolderUsingEventVersion() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingLlmTriggerService triggerService = new RecordingLlmTriggerService();
        ExplanationCache explanationCache = new ExplanationCache();
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        LlmRiskEventListener listener = new LlmRiskEventListener(
                new LlmRiskContextAssembler(new EncounterClassifier(new EncounterProperties())),
                holder,
                triggerService,
                explanationCache,
                publisher,
                new SceneRiskStateTracker(new LlmProperties(), null,
                        new StaticListableBeanFactory().getBeanProvider(com.whut.map.map_service.llm.agent.trigger.AdvisoryTriggerPort.class))
        );

        listener.onRiskAssessmentCompleted(riskEvent(7L, false));

        assertThat(holder.getVersion()).isEqualTo(7L);
        assertThat(holder.getCurrent()).isNotNull();
        assertThat(holder.getCurrent().getOwnShip().getId()).isEqualTo("own-1");
        assertThat(holder.getCurrent().getTargets()).hasSize(1);
        assertThat(holder.getCurrent().getTargets().get(0).getTargetId()).isEqualTo("target-1");
        assertThat(triggerService.lastContext).isNull();
        assertThat(publisher.lastExplanation).isNull();
        assertThat(publisher.lastErrorCode).isNull();
        assertThat(explanationCache.shouldAccept("target-1")).isTrue();
    }

    @Test
    void listenerPublishesExplanationPayloadWhenTriggerServiceReturnsExplanation() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingLlmTriggerService triggerService = new RecordingLlmTriggerService();
        ExplanationCache explanationCache = new ExplanationCache();
        triggerService.explanationToSend = LlmExplanation.builder()
                .source(LlmExplanation.SOURCE_LLM)
                .provider("gemini")
                .targetId("target-1")
                .riskLevel("WARNING")
                .text("Keep clear.")
                .timestamp("2026-04-11T09:30:00Z")
                .build();
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        LlmRiskEventListener listener = new LlmRiskEventListener(
                new LlmRiskContextAssembler(new EncounterClassifier(new EncounterProperties())),
                holder,
                triggerService,
                explanationCache,
                publisher,
                new SceneRiskStateTracker(new LlmProperties(), null,
                        new StaticListableBeanFactory().getBeanProvider(com.whut.map.map_service.llm.agent.trigger.AdvisoryTriggerPort.class))
        );

        listener.onRiskAssessmentCompleted(riskEvent(8L, true));

        assertThat(triggerService.lastContext).isNotNull();
        assertThat(holder.getVersion()).isEqualTo(8L);
        assertThat(publisher.lastExplanation).isNotNull();
        assertThat(publisher.lastExplanation.getRiskObjectId()).isEqualTo("risk-1");
        assertThat(publisher.lastExplanation.getTargetId()).isEqualTo("target-1");
        assertThat(publisher.lastExplanation.getProvider()).isEqualTo("gemini");
        assertThat(publisher.lastExplanation.getText()).isEqualTo("Keep clear.");
        assertThat(explanationCache.getText("target-1")).isEqualTo("Keep clear.");
    }

    @Test
    void listenerDropsLateExplanationWhenTargetDisappears() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingLlmTriggerService triggerService = new RecordingLlmTriggerService();
        triggerService.autoDispatch = false;
        ExplanationCache explanationCache = new ExplanationCache();
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        LlmRiskEventListener listener = new LlmRiskEventListener(
                new LlmRiskContextAssembler(new EncounterClassifier(new EncounterProperties())),
                holder,
                triggerService,
                explanationCache,
                publisher,
                new SceneRiskStateTracker(new LlmProperties(), null,
                        new StaticListableBeanFactory().getBeanProvider(com.whut.map.map_service.llm.agent.trigger.AdvisoryTriggerPort.class))
        );

        listener.onRiskAssessmentCompleted(riskEvent(8L, true));
        listener.onRiskAssessmentCompleted(riskEventWithoutTarget(9L));
        triggerService.dispatchStoredExplanation(LlmExplanation.builder()
                .source(LlmExplanation.SOURCE_LLM)
                .provider("gemini")
                .targetId("target-1")
                .riskLevel("WARNING")
                .text("Too late.")
                .timestamp("2026-04-11T09:35:00Z")
                .build());

        assertThat(publisher.explanationPublishCount).isZero();
        assertThat(explanationCache.getText("target-1")).isNull();
    }

    @Test
    void listenerDropsLateExplanationWhenTargetBecomesSafe() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingLlmTriggerService triggerService = new RecordingLlmTriggerService();
        triggerService.autoDispatch = false;
        ExplanationCache explanationCache = new ExplanationCache();
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        LlmRiskEventListener listener = new LlmRiskEventListener(
                new LlmRiskContextAssembler(new EncounterClassifier(new EncounterProperties())),
                holder,
                triggerService,
                explanationCache,
                publisher,
                new SceneRiskStateTracker(new LlmProperties(), null,
                        new StaticListableBeanFactory().getBeanProvider(com.whut.map.map_service.llm.agent.trigger.AdvisoryTriggerPort.class))
        );

        listener.onRiskAssessmentCompleted(riskEvent(8L, true));
        listener.onRiskAssessmentCompleted(riskEventWithRiskLevel(9L, false, "SAFE"));
        triggerService.dispatchStoredExplanation(LlmExplanation.builder()
                .source(LlmExplanation.SOURCE_LLM)
                .provider("gemini")
                .targetId("target-1")
                .riskLevel("WARNING")
                .text("Too late.")
                .timestamp("2026-04-11T09:35:00Z")
                .build());

        assertThat(publisher.explanationPublishCount).isZero();
        assertThat(explanationCache.getText("target-1")).isNull();
    }

    private RiskAssessmentCompletedEvent riskEvent(long version, boolean triggerExplanations) {
        return riskEventWithRiskLevel(version, triggerExplanations, "WARNING");
    }

    private RiskAssessmentCompletedEvent riskEventWithRiskLevel(long version, boolean triggerExplanations, String riskLevel) {
        ShipStatus ownShip = ShipStatus.builder()
                .id("own-1")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(12.0)
                .cog(90.0)
                .heading(90.0)
                .build();
        ShipStatus targetShip = ShipStatus.builder()
                .id("target-1")
                .role(ShipRole.TARGET_SHIP)
                .longitude(120.1)
                .latitude(30.1)
                .sog(10.0)
                .cog(180.0)
                .build();
        return new RiskAssessmentCompletedEvent(
                version,
                ownShip,
                List.of(ownShip, targetShip),
                Map.of("target-1", CpaTcpaResult.builder()
                        .targetMmsi("target-1")
                        .cpaDistance(500.0)
                        .tcpaTime(240.0)
                        .isApproaching(true)
                        .build()),
                RiskAssessmentResult.builder()
                        .targetAssessments(Map.of("target-1", TargetRiskAssessment.builder()
                                .targetId("target-1")
                                .riskLevel(riskLevel)
                                .cpaDistanceMeters(500.0)
                                .tcpaSeconds(240.0)
                                .approaching(true)
                                .explanationText("Rule explanation")
                                .build()))
                        .build(),
                "risk-1",
                triggerExplanations
        );
    }

    private RiskAssessmentCompletedEvent riskEventWithoutTarget(long version) {
        ShipStatus ownShip = ShipStatus.builder()
                .id("own-1")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(12.0)
                .cog(90.0)
                .heading(90.0)
                .build();
        return new RiskAssessmentCompletedEvent(
                version,
                ownShip,
                List.of(ownShip),
                Map.of(),
                RiskAssessmentResult.builder()
                        .targetAssessments(Map.of())
                        .build(),
                "risk-1",
                false
        );
    }

    private static final class RecordingLlmTriggerService extends LlmTriggerService {
        private LlmRiskContext lastContext;
        private LlmExplanation explanationToSend;
        private boolean autoDispatch = true;
        private Consumer<LlmExplanation> storedExplanationConsumer;

        RecordingLlmTriggerService() {
            super(new LlmProperties(), null);
        }

        @Override
        public void triggerExplanationsIfNeeded(
                LlmRiskContext context,
                Consumer<LlmExplanation> onExplanation,
                BiConsumer<LlmRiskTargetContext, LlmExplanationService.LlmExplanationError> onError
        ) {
            this.lastContext = context;
            this.storedExplanationConsumer = onExplanation;
            if (autoDispatch && explanationToSend != null) {
                onExplanation.accept(explanationToSend);
            }
        }

        void dispatchStoredExplanation(LlmExplanation explanation) {
            if (storedExplanationConsumer != null) {
                storedExplanationConsumer.accept(explanation);
            }
        }
    }

    private static final class RecordingRiskStreamPublisher extends RiskStreamPublisher {
        private ExplanationPayload lastExplanation;
        private String lastErrorCode;
        private int explanationPublishCount;

        RecordingRiskStreamPublisher() {
            super(null, null);
        }

        @Override
        public void publishExplanation(ExplanationPayload payload) {
            this.lastExplanation = payload;
            this.explanationPublishCount++;
        }

        @Override
        public void publishError(String errorCode, String errorMessage, String targetId) {
            this.lastErrorCode = errorCode;
        }
    }
}
