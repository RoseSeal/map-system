package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.config.properties.EncounterProperties;
import com.whut.map.map_service.config.properties.AisQualityProperties;
import com.whut.map.map_service.config.properties.ShipStateProperties;
import com.whut.map.map_service.pipeline.assembler.RiskObjectAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.RiskVisualizationAssembler;
import com.whut.map.map_service.pipeline.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.domain.QualityFlag;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.event.RiskFrame;
import com.whut.map.map_service.engine.ShipKinematicQualityChecker;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.store.DerivedTargetStateStore;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

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
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                new DerivedTargetStateStore()
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
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                new DerivedTargetStateStore()
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
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                new DerivedTargetStateStore()
        );
    }

    @Test
    void dispatchPersistsKinematicConfidenceAndFlags() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipStateStore shipStateStore = shipStateStore();
        com.whut.map.map_service.store.ShipTrajectoryStore trajectoryStore = new com.whut.map.map_service.store.ShipTrajectoryStore();
        ShipStatus ownShip = ship("ownShip", ShipRole.OWN_SHIP, 120.0000, 30.0000, 8.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        ShipStatus previousTarget = ship("target-1", ShipRole.TARGET_SHIP, 120.0000, 30.0000, 6.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        previousTarget.setConfidence(0.95);
        shipStateStore.update(ownShip);
        shipStateStore.update(previousTarget);

        ShipStatus targetUpdate = ship("target-1", ShipRole.TARGET_SHIP, 120.1000, 30.0000, 20.0, OffsetDateTime.parse("2026-04-12T09:00:30+08:00"));
        targetUpdate.setConfidence(0.95);
        targetUpdate.setCog(220.0);
        targetUpdate.setHeading(220.0);
        targetUpdate.setQualityFlags(Set.of(QualityFlag.MISSING_HEADING));

        ShipDispatcher dispatcher = new ShipDispatcher(
                new RecordingShipDomainEngine(ShipDomainResult.builder().shapeType(ShipDomainResult.SHAPE_ELLIPSE).build()),
                new RecordingCvPredictionEngine(),
                new StubCpaTcpaBatchCalculator(Map.of()),
                new EncounterClassifier(new EncounterProperties()),
                new StubRiskAssessmentEngine(RiskAssessmentResult.empty()),
                riskObjectAssembler(),
                shipStateStore,
                trajectoryStore,
                publisher,
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                new DerivedTargetStateStore()
        );

        dispatcher.dispatch(targetUpdate);

        ShipStatus stored = shipStateStore.get("target-1");
        assertThat(stored).isNotNull();
        assertThat(stored.getConfidence()).isCloseTo(0.2, within(1e-9));
        assertThat(stored.getQualityFlags()).containsExactlyInAnyOrder(
                QualityFlag.MISSING_HEADING,
                QualityFlag.POSITION_JUMP,
                QualityFlag.SOG_JUMP,
                QualityFlag.COG_JUMP
        );

        List<ShipStatus> history = trajectoryStore.getHistory("target-1");
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getQualityFlags()).containsExactlyInAnyOrder(
                QualityFlag.MISSING_HEADING,
                QualityFlag.POSITION_JUMP,
                QualityFlag.SOG_JUMP,
                QualityFlag.COG_JUMP
        );
    }

    @Test
    void dispatchFallsBackToFullWhenDerivedCacheIsMissing() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipStateStore shipStateStore = shipStateStore();
        com.whut.map.map_service.store.ShipTrajectoryStore trajectoryStore = new com.whut.map.map_service.store.ShipTrajectoryStore();
        DerivedTargetStateStore derivedStore = new DerivedTargetStateStore();
        ShipStatus targetShip = ship("target-1", ShipRole.TARGET_SHIP, 120.0100, 30.0100, 12.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        ShipStatus ownShip = ship("ownShip", ShipRole.OWN_SHIP, 120.0000, 30.0000, 8.0, OffsetDateTime.parse("2026-04-12T09:00:05+08:00"));
        ShipStatus targetUpdate = ship("target-1", ShipRole.TARGET_SHIP, 120.0200, 30.0100, 12.0, OffsetDateTime.parse("2026-04-12T09:00:10+08:00"));

        RecordingShipDomainEngine shipDomainEngine = new RecordingShipDomainEngine(
                ShipDomainResult.builder().shapeType(ShipDomainResult.SHAPE_ELLIPSE).build()
        );
        StubRiskAssessmentEngine riskAssessmentEngine = new StubRiskAssessmentEngine(RiskAssessmentResult.empty());
        ShipDispatcher dispatcher = new ShipDispatcher(
                shipDomainEngine,
                new RecordingCvPredictionEngine(),
                new StubCpaTcpaBatchCalculator(Map.of()),
                new EncounterClassifier(new EncounterProperties()),
                riskAssessmentEngine,
                riskObjectAssembler(),
                shipStateStore,
                trajectoryStore,
                publisher,
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                derivedStore
        );

        shipStateStore.update(targetShip);
        trajectoryStore.append(targetShip);

        dispatcher.dispatch(ownShip);
        assertThat(shipDomainEngine.consumeCount).isEqualTo(1);
        assertThat(derivedStore.get("target-1")).isNotNull();

        derivedStore.remove("target-1");
        dispatcher.dispatch(targetUpdate);

        assertThat(shipDomainEngine.consumeCount).isEqualTo(2);
        assertThat(derivedStore.get("target-1")).isNotNull();
    }

    @Test
    void dispatchIncrementalReusesCachedRiskAssessments() {
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        RecordingEventPublisher eventPublisher = new RecordingEventPublisher();
        ShipStateStore shipStateStore = shipStateStore();
        com.whut.map.map_service.store.ShipTrajectoryStore trajectoryStore = new com.whut.map.map_service.store.ShipTrajectoryStore();
        DerivedTargetStateStore derivedStore = new DerivedTargetStateStore();
        ShipStatus targetShip = ship("target-1", ShipRole.TARGET_SHIP, 120.0100, 30.0100, 12.0, OffsetDateTime.parse("2026-04-12T09:00:00+08:00"));
        ShipStatus ownShip = ship("ownShip", ShipRole.OWN_SHIP, 120.0000, 30.0000, 8.0, OffsetDateTime.parse("2026-04-12T09:00:05+08:00"));
        ShipStatus targetUpdate = ship("target-1", ShipRole.TARGET_SHIP, 120.0200, 30.0100, 12.0, OffsetDateTime.parse("2026-04-12T09:00:10+08:00"));

        RecordingShipDomainEngine shipDomainEngine = new RecordingShipDomainEngine(
                ShipDomainResult.builder().shapeType(ShipDomainResult.SHAPE_ELLIPSE).build()
        );
        StubRiskAssessmentEngine riskAssessmentEngine = new StubRiskAssessmentEngine(RiskAssessmentResult.empty());
        ShipDispatcher dispatcher = new ShipDispatcher(
                shipDomainEngine,
                new RecordingCvPredictionEngine(),
                new StubCpaTcpaBatchCalculator(Map.of()),
                new EncounterClassifier(new EncounterProperties()),
                riskAssessmentEngine,
                riskObjectAssembler(),
                shipStateStore,
                trajectoryStore,
                publisher,
                eventPublisher,
                new ShipKinematicQualityChecker(new AisQualityProperties()),
                derivedStore
        );

        shipStateStore.update(targetShip);
        trajectoryStore.append(targetShip);

        dispatcher.dispatch(ownShip);
        dispatcher.dispatch(targetUpdate);

        assertThat(shipDomainEngine.consumeCount).isEqualTo(1);
        assertThat(riskAssessmentEngine.consumeCount).isZero();
        assertThat(riskAssessmentEngine.buildTargetAssessmentCount).isEqualTo(2);
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
        private int consumeCount;

        RecordingShipDomainEngine(ShipDomainResult result) {
            super(new com.whut.map.map_service.config.properties.ShipDomainProperties());
            this.result = result;
        }

        @Override
        public ShipDomainResult consume(ShipStatus message) {
            this.lastConsumedShip = message;
            this.consumeCount++;
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

        @Override
        public CpaTcpaResult calculateOne(ShipStatus ownShip, ShipStatus targetShip) {
            return results.get(targetShip.getId());
        }
    }

    private static final class StubRiskAssessmentEngine extends RiskAssessmentEngine {
        private final RiskAssessmentResult result;
        private ShipDomainResult lastDomainResult;
        private int consumeCount;
        private int buildTargetAssessmentCount;

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
            this.consumeCount++;
            return result;
        }

        @Override
        public TargetRiskAssessment buildTargetAssessment(
                String targetId,
                CpaTcpaResult cpaResult,
                ShipStatus ownShip,
                ShipStatus targetShip,
                ShipDomainResult domainResult,
                com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult predictionResult,
                com.whut.map.map_service.engine.encounter.EncounterClassificationResult encounterResult
        ) {
            this.lastDomainResult = domainResult;
            this.buildTargetAssessmentCount++;
            return TargetRiskAssessment.builder()
                    .targetId(targetId)
                    .riskLevel(RiskConstants.SAFE)
                    .cpaDistanceMeters(0.0)
                    .tcpaSeconds(0.0)
                    .approaching(false)
                    .explanationSource(RiskConstants.EXPLANATION_SOURCE_RULE)
                    .explanationText(RiskConstants.EXPLANATION_TEXT_DERIVED)
                    .riskScore(0.0)
                    .riskConfidence(1.0)
                    .build();
        }
    }
}
