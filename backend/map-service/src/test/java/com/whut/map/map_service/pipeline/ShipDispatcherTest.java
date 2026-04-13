package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.config.properties.EncounterProperties;
import com.whut.map.map_service.config.properties.ShipStateProperties;
import com.whut.map.map_service.pipeline.assembler.RiskObjectAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.RiskVisualizationAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.event.RiskFrame;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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

    @Test
    void dispatchUsesOwnShipDomainAndEncounterClassification() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipStateStore shipStateStore = shipStateStore();
        ShipStatus ownShip = ship("ownShip", ShipRole.OWN_SHIP, 120.0000, 30.0000, 8.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        ShipStatus targetShip = ship("target-1", ShipRole.TARGET_SHIP, 120.0100, 30.0100, 12.0, OffsetDateTime.parse("2026-04-12T09:00:05+08:00"));
        ShipDomainResult domainResult = ShipDomainResult.builder()
                .foreNm(0.75)
                .aftNm(0.15)
                .portNm(0.3)
                .stbdNm(0.3)
                .shapeType(ShipDomainResult.SHAPE_ELLIPSE)
                .build();
        RecordingShipDomainEngine shipDomainEngine = new RecordingShipDomainEngine(domainResult);
        RecordingCvPredictionEngine cvPredictionEngine = new RecordingCvPredictionEngine();
        StubCpaTcpaBatchCalculator cpaTcpaBatchCalculator = new StubCpaTcpaBatchCalculator(Map.of());
        StubRiskAssessmentEngine riskAssessmentEngine = new StubRiskAssessmentEngine(RiskAssessmentResult.empty());
        EncounterClassifier encounterClassifier = new EncounterClassifier(new EncounterProperties());

        shipStateStore.update(ownShip);

        ShipDispatcher dispatcher = new ShipDispatcher(
                shipDomainEngine,
                cvPredictionEngine,
                cpaTcpaBatchCalculator,
                encounterClassifier,
                riskAssessmentEngine,
                riskObjectAssembler(),
                shipStateStore,
                new com.whut.map.map_service.store.ShipTrajectoryStore(),
                publisher,
                eventPublisher
        );

        dispatcher.dispatch(targetShip);

        assertThat(shipDomainEngine.lastConsumedShip).isNotNull();
        assertThat(shipDomainEngine.lastConsumedShip.getId()).isEqualTo("ownShip");
        assertThat(cvPredictionEngine.lastConsumedShip).isEqualTo(targetShip);
        assertThat(publisher.publishedFrame).isNotNull();
        
        // Verify encounter_type exists for target
        List<Map<String, Object>> targets = (List<Map<String, Object>>) publisher.publishedFrame.riskObject().getTargets();
        Map<String, Object> targetMap = targets.get(0);
        Map<String, Object> riskAssessment = castMap(targetMap.get("risk_assessment"));
        assertThat(riskAssessment).containsKey("encounter_type");
    }

    @Test
    void refreshAfterCleanupRecomputesOwnShipDomain() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipStateStore shipStateStore = shipStateStore();
        ShipStatus ownShip = ship("ownShip", ShipRole.OWN_SHIP, 120.0000, 30.0000, 8.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        ShipDomainResult domainResult = ShipDomainResult.builder()
                .foreNm(0.6)
                .aftNm(0.12)
                .portNm(0.24)
                .stbdNm(0.24)
                .shapeType(ShipDomainResult.SHAPE_ELLIPSE)
                .build();
        RecordingShipDomainEngine shipDomainEngine = new RecordingShipDomainEngine(domainResult);
        StubCpaTcpaBatchCalculator cpaTcpaBatchCalculator = new StubCpaTcpaBatchCalculator(Map.of());
        StubRiskAssessmentEngine riskAssessmentEngine = new StubRiskAssessmentEngine(RiskAssessmentResult.empty());
        EncounterClassifier encounterClassifier = new EncounterClassifier(new EncounterProperties());

        shipStateStore.update(ownShip);

        ShipDispatcher dispatcher = new ShipDispatcher(
                shipDomainEngine,
                new RecordingCvPredictionEngine(),
                cpaTcpaBatchCalculator,
                encounterClassifier,
                riskAssessmentEngine,
                riskObjectAssembler(),
                shipStateStore,
                new com.whut.map.map_service.store.ShipTrajectoryStore(),
                publisher,
                eventPublisher
        );

        dispatcher.refreshAfterCleanup();

        assertThat(shipDomainEngine.lastConsumedShip).isNotNull();
        assertThat(shipDomainEngine.lastConsumedShip.getId()).isEqualTo("ownShip");
        assertThat(publisher.publishedFrame).isNotNull();
    }

    private ShipDispatcher dispatcher(
            RecordingRiskStreamPublisher publisher,
            RecordingEventPublisher eventPublisher
    ) {
        return new ShipDispatcher(
                (ShipDomainEngine) null,
                (CvPredictionEngine) null,
                (CpaTcpaBatchCalculator) null,
                (EncounterClassifier) null,
                (RiskAssessmentEngine) null,
                (RiskObjectAssembler) null,
                (ShipStateStore) null,
                (com.whut.map.map_service.store.ShipTrajectoryStore) null,
                publisher,
                eventPublisher
        );
    }

    private RiskObjectAssembler riskObjectAssembler() {
        return new RiskObjectAssembler(
                new RiskObjectMetaAssembler(),
                new OwnShipAssembler(),
                new TargetAssembler(new RiskVisualizationAssembler())
        );
    }

    private ShipStateStore shipStateStore() {
        ShipStateProperties properties = new ShipStateProperties();
        properties.setExpireAfterSeconds(300L);
        properties.setCleanupIntervalSeconds(300L);
        return new ShipStateStore(properties);
    }

    private ShipStatus ship(String id, ShipRole role, double longitude, double latitude) {
        return ship(id, role, longitude, latitude, 0.0, null);
    }

    private ShipStatus ship(String id, ShipRole role, double longitude, double latitude, double sog, OffsetDateTime msgTime) {
        return ShipStatus.builder()
                .id(id)
                .role(role)
                .longitude(longitude)
                .latitude(latitude)
                .sog(sog)
                .cog(90.0)
                .msgTime(msgTime)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
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

    private static final class RecordingShipDomainEngine extends ShipDomainEngine {
        private final ShipDomainResult result;
        private ShipStatus lastConsumedShip;

        RecordingShipDomainEngine(ShipDomainResult result) {
            super(new com.whut.map.map_service.config.properties.ShipDomainProperties());
            this.result = result;
        }

        @Override
        public ShipDomainResult consume(ShipStatus message) {
            this.lastConsumedShip = message;
            return result;
        }
    }

    private static final class RecordingCvPredictionEngine extends CvPredictionEngine {
        private ShipStatus lastConsumedShip;

        RecordingCvPredictionEngine() {
            super(new com.whut.map.map_service.config.properties.TrajectoryPredictionProperties());
        }

        @Override
        public com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult consume(
                ShipStatus message,
                List<ShipStatus> history
        ) {
            this.lastConsumedShip = message;
            return null;
        }
    }

    private static final class StubCpaTcpaBatchCalculator extends CpaTcpaBatchCalculator {
        private final Map<String, CpaTcpaResult> results;

        StubCpaTcpaBatchCalculator(Map<String, CpaTcpaResult> results) {
            super(null);
            this.results = results;
        }

        @Override
        public Map<String, CpaTcpaResult> calculateAll(ShipStatus ownShip, Collection<ShipStatus> ships) {
            return results;
        }
    }

    private static final class StubRiskAssessmentEngine extends RiskAssessmentEngine {
        private final RiskAssessmentResult result;
        private ShipDomainResult lastDomainResult;

        StubRiskAssessmentEngine(RiskAssessmentResult result) {
            super(new com.whut.map.map_service.config.properties.RiskAssessmentProperties(), null, null, null);
            this.result = result;
        }

        @Override
        public RiskAssessmentResult consume(
                ShipStatus ownShip,
                Collection<ShipStatus> allShips,
                Map<String, CpaTcpaResult> cpaResults,
                ShipDomainResult shipDomainResult,
                Map<String, com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult> cvPredictionResults,
                Map<String, com.whut.map.map_service.engine.encounter.EncounterClassificationResult> encounterResults
        ) {
            this.lastDomainResult = shipDomainResult;
            return result;
        }
    }
}
