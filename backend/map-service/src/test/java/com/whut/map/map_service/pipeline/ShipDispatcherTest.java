package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.pipeline.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.event.RiskFrame;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShipDispatcherTest {

    @Test
    void publishRiskSnapshotPublishesRiskFrameAndEvent() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipDispatcher dispatcher = dispatcher(publisher, eventPublisher);
        RiskObjectDto riskObject = RiskObjectDto.builder().riskObjectId("risk-1").build();
        RiskDispatchSnapshot snapshot = new RiskDispatchSnapshot(
                ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000),
                java.util.List.of(),
                Map.of(),
                RiskAssessmentResult.empty(),
                riskObject,
                true
        );

        dispatcher.publishRiskSnapshot(snapshot);

        assertThat(publisher.publishedFrame).isNotNull();
        assertThat(publisher.publishedFrame.riskObject()).isSameAs(riskObject);
        assertThat(eventPublisher.publishedEvent).isInstanceOf(RiskAssessmentCompletedEvent.class);
        RiskAssessmentCompletedEvent event = (RiskAssessmentCompletedEvent) eventPublisher.publishedEvent;
        assertThat(event.snapshotVersion()).isEqualTo(1L);
        assertThat(event.riskObjectId()).isEqualTo("risk-1");
        assertThat(event.triggerExplanations()).isTrue();
    }

    private ShipDispatcher dispatcher(
            RecordingRiskStreamPublisher publisher,
            RecordingEventPublisher eventPublisher
    ) {
        return new ShipDispatcher(
                (ShipDomainEngine) null,
                (CvPredictionEngine) null,
                (CpaTcpaBatchCalculator) null,
                (RiskAssessmentEngine) null,
                (RiskObjectAssembler) null,
                (ShipStateStore) null,
                publisher,
                eventPublisher
        );
    }

    private ShipStatus ship(String id, ShipRole role, double longitude, double latitude) {
        return ShipStatus.builder()
                .id(id)
                .role(role)
                .longitude(longitude)
                .latitude(latitude)
                .build();
    }

    private static final class RecordingRiskStreamPublisher extends RiskStreamPublisher {
        private RiskFrame publishedFrame;

        RecordingRiskStreamPublisher() {
            super(null, null);
        }

        @Override
        public void publishRiskFrame(RiskFrame frame) {
            this.publishedFrame = frame;
            if (frame.beforePublish() != null) {
                frame.beforePublish().accept(1L);
            }
        }
    }

    private static final class RecordingEventPublisher implements ApplicationEventPublisher {
        private Object publishedEvent;

        @Override
        public void publishEvent(Object event) {
            this.publishedEvent = event;
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            this.publishedEvent = event;
        }
    }
}
