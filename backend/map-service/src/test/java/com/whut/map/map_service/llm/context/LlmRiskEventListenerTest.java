package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.sse.ExplanationPayload;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.service.LlmExplanationService;
import com.whut.map.map_service.llm.service.LlmTriggerService;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import org.junit.jupiter.api.Test;

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
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        LlmRiskEventListener listener = new LlmRiskEventListener(
                new LlmRiskContextAssembler(),
                holder,
                triggerService,
                publisher
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
    }

    @Test
    void listenerPublishesExplanationPayloadWhenTriggerServiceReturnsExplanation() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingLlmTriggerService triggerService = new RecordingLlmTriggerService();
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
                new LlmRiskContextAssembler(),
                holder,
                triggerService,
                publisher
        );

        listener.onRiskAssessmentCompleted(riskEvent(8L, true));

        assertThat(triggerService.lastContext).isNotNull();
        assertThat(holder.getVersion()).isEqualTo(8L);
        assertThat(publisher.lastExplanation).isNotNull();
        assertThat(publisher.lastExplanation.getRiskObjectId()).isEqualTo("risk-1");
        assertThat(publisher.lastExplanation.getTargetId()).isEqualTo("target-1");
        assertThat(publisher.lastExplanation.getProvider()).isEqualTo("gemini");
        assertThat(publisher.lastExplanation.getText()).isEqualTo("Keep clear.");
    }

    private RiskAssessmentCompletedEvent riskEvent(long version, boolean triggerExplanations) {
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
                                .riskLevel("WARNING")
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

    private static final class RecordingLlmTriggerService extends LlmTriggerService {
        private LlmRiskContext lastContext;
        private LlmExplanation explanationToSend;

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
            if (explanationToSend != null) {
                onExplanation.accept(explanationToSend);
            }
        }
    }

    private static final class RecordingRiskStreamPublisher extends RiskStreamPublisher {
        private ExplanationPayload lastExplanation;
        private String lastErrorCode;

        RecordingRiskStreamPublisher() {
            super(null, null);
        }

        @Override
        public void publishExplanation(ExplanationPayload payload) {
            this.lastExplanation = payload;
        }

        @Override
        public void publishError(String errorCode, String errorMessage, String targetId) {
            this.lastErrorCode = errorCode;
        }
    }
}
