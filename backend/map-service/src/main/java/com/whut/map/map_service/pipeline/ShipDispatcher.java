package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.pipeline.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.event.RiskFrame;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.store.ShipTrajectoryStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class ShipDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaBatchCalculator cpaTcpaBatchCalculator;
    private final EncounterClassifier encounterClassifier;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final ShipStateStore shipStateStore;
    private final ShipTrajectoryStore shipTrajectoryStore;
    private final RiskStreamPublisher riskStreamPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            EncounterClassifier encounterClassifier,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore,
            ShipTrajectoryStore shipTrajectoryStore,
            RiskStreamPublisher riskStreamPublisher,
            ApplicationEventPublisher applicationEventPublisher

    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaBatchCalculator = cpaTcpaBatchCalculator;
        this.encounterClassifier = encounterClassifier;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.shipStateStore = shipStateStore;
        this.shipTrajectoryStore = shipTrajectoryStore;
        this.riskStreamPublisher = riskStreamPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    public void dispatch(ShipStatus message) {
        ShipDispatchContext context = prepareContext(message);
        if (context == null) {
            return;
        }

        ShipDerivedOutputs outputs = runDerivations(context);
        RiskDispatchSnapshot snapshot = buildRiskSnapshot(context, outputs, true);
        if (snapshot == null) {
            return;
        }

        publishRiskSnapshot(snapshot);
        logTargetCpa(context, outputs.cpaResults());
    }

    private ShipDispatchContext prepareContext(ShipStatus message) {
        if (message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, id: {}", message.getId());
            return null;
        }

        if (!shipStateStore.update(message)) {
            return null;
        }

        Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();
        removedIds.forEach(shipTrajectoryStore::remove);
        shipTrajectoryStore.append(message);

        return new ShipDispatchContext(message, shipStateStore.getOwnShip(), shipStateStore.getAll());
    }

    private Map<String, CvPredictionResult> batchPredict(String ownShipId, Collection<ShipStatus> allShips) {
        Map<String, CvPredictionResult> results = new HashMap<>();
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
                continue;
            }
            results.put(ship.getId(), cvPredictionEngine.consume(ship));
        }
        return results;
    }

    private Map<String, EncounterClassificationResult> batchClassify(
            ShipStatus ownShip, Collection<ShipStatus> allShips) {
        if (ownShip == null || allShips == null) {
            return Map.of();
        }
        Map<String, EncounterClassificationResult> results = new HashMap<>();
        String ownId = ownShip.getId();
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownId)) {
                continue;
            }
            results.put(ship.getId(), encounterClassifier.classify(ownShip, ship));
        }
        return results;
    }

    private ShipDerivedOutputs runDerivations(ShipDispatchContext context) {
        ShipDomainResult shipDomainResult = null;

        if (context.hasOwnShip()) {
            shipDomainResult = shipDomainEngine.consume(context.ownShip());
        }

        String ownShipId = context.hasOwnShip() ? context.ownShip().getId() : null;
        Map<String, CvPredictionResult> cvPredictionResults = batchPredict(ownShipId, context.allShips());

        Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(
                context.ownShip(),
                context.allShips()
        );

        Map<String, EncounterClassificationResult> encounterResults =
                batchClassify(context.ownShip(), context.allShips());

        return new ShipDerivedOutputs(shipDomainResult, cvPredictionResults, cpaResults, encounterResults);
    }

    private RiskDispatchSnapshot buildRiskSnapshot(
            ShipDispatchContext context,
            ShipDerivedOutputs outputs,
            boolean triggerExplanations
    ) {
        if (!context.hasOwnShip()) {
            log.debug("Skipping RiskObject broadcast until ownShip is available, incoming id={}", context.message().getId());
            return null;
        }

        RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                outputs.shipDomainResult(),
                null
        );

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                riskResult,
                outputs.shipDomainResult(),
                outputs.cvPredictionResults(),
                outputs.encounterResults()
        );
        if (dto == null) {
            return null;
        }

        return new RiskDispatchSnapshot(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                riskResult,
                dto,
                triggerExplanations
        );
    }

    void publishRiskSnapshot(RiskDispatchSnapshot snapshot) {
        riskStreamPublisher.publishRiskFrame(new RiskFrame(
                snapshot.riskObject(),
                snapshotVersion -> applicationEventPublisher.publishEvent(new RiskAssessmentCompletedEvent(
                        snapshotVersion,
                        snapshot.ownShip(),
                        snapshot.allShips(),
                        snapshot.cpaResults(),
                        snapshot.riskAssessmentResult(),
                        snapshot.riskObject().getRiskObjectId(),
                        snapshot.triggerExplanations()
                ))
        ));
    }

    public void refreshAfterCleanup() {
        ShipStatus ownShip = shipStateStore.getOwnShip();
        if (ownShip == null) {
            return;
        }

        Collection<ShipStatus> allShips = shipStateStore.getAll().values();
        ShipDomainResult domainResult = shipDomainEngine.consume(ownShip);

        Map<String, CvPredictionResult> cvPredictionResults = batchPredict(ownShip.getId(), allShips);
        Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(ownShip, allShips);

        RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
                ownShip, allShips, cpaResults, domainResult, null);

        Map<String, EncounterClassificationResult> encounterResults = batchClassify(ownShip, allShips);

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                ownShip, allShips, cpaResults, riskResult, domainResult, cvPredictionResults, encounterResults);
        if (dto == null) {
            return;
        }

        publishRiskSnapshot(new RiskDispatchSnapshot(
                ownShip,
                allShips,
                cpaResults,
                riskResult,
                dto,
                false
        ));
        log.debug("Published refreshed risk snapshot after cleanup, targets={}", allShips.size() - 1);
    }

    private void logTargetCpa(ShipDispatchContext context, Map<String, CpaTcpaResult> cpaResults) {
        if (context.message().getRole() != ShipRole.TARGET_SHIP || !cpaResults.containsKey(context.message().getId())) {
            return;
        }

        CpaTcpaResult cpaResult = cpaResults.get(context.message().getId());
        log.debug("CPA={}m, TCPA={}s, target={}",
                String.format("%.1f", cpaResult.getCpaDistance()),
                String.format("%.1f", cpaResult.getTcpaTime()),
                cpaResult.getTargetMmsi());
    }
}
