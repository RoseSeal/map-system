package com.whut.map.map_service.risk.pipeline;

import com.whut.map.map_service.risk.environment.EnvironmentContextService;
import com.whut.map.map_service.risk.environment.EnvironmentRefreshResult;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.risk.environment.OwnShipPositionHolder;
import com.whut.map.map_service.risk.pipeline.assembler.RiskObjectAssembler;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.risk.event.RiskFrame;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaBatchCalculator;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.ShipKinematicQualityChecker;
import com.whut.map.map_service.risk.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.tracking.store.ShipStateStore;
import com.whut.map.map_service.tracking.store.ShipTrajectoryStore;
import com.whut.map.map_service.tracking.store.DerivedTargetStateStore;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Component
public class ShipDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaBatchCalculator cpaTcpaBatchCalculator;
    private final PredictedCpaTcpaBatchCalculator predictedCpaTcpaBatchCalculator;
    private final EncounterClassifier encounterClassifier;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final ShipStateStore shipStateStore;
    private final ShipTrajectoryStore shipTrajectoryStore;
    private final RiskStreamPublisher riskStreamPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final ShipKinematicQualityChecker kinematicChecker;
    private final DerivedTargetStateStore derivedTargetStateStore;
    private final EnvironmentContextService environmentContextService;
    private final OwnShipPositionHolder ownShipPositionHolder;
    private volatile ShipDomainResult cachedOwnShipDomainResult;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            PredictedCpaTcpaBatchCalculator predictedCpaTcpaBatchCalculator,
            EncounterClassifier encounterClassifier,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore,
            ShipTrajectoryStore shipTrajectoryStore,
            RiskStreamPublisher riskStreamPublisher,
            ApplicationEventPublisher applicationEventPublisher,
            ShipKinematicQualityChecker kinematicChecker,
            DerivedTargetStateStore derivedTargetStateStore,
            EnvironmentContextService environmentContextService,
            OwnShipPositionHolder ownShipPositionHolder
    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaBatchCalculator = cpaTcpaBatchCalculator;
        this.predictedCpaTcpaBatchCalculator = Objects.requireNonNull(
                predictedCpaTcpaBatchCalculator,
                "predictedCpaTcpaBatchCalculator"
        );
        this.encounterClassifier = encounterClassifier;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.shipStateStore = shipStateStore;
        this.shipTrajectoryStore = shipTrajectoryStore;
        this.riskStreamPublisher = riskStreamPublisher;
        this.applicationEventPublisher = applicationEventPublisher;
        this.kinematicChecker = kinematicChecker;
        this.derivedTargetStateStore = derivedTargetStateStore;
        this.environmentContextService = environmentContextService;
        this.ownShipPositionHolder = ownShipPositionHolder;
    }

    public void dispatch(ShipStatus message) {
        long start = System.currentTimeMillis();
        ShipDispatchContext context = prepareContext(message);
        if (context == null) {
            return;
        }

        long prepTime = System.currentTimeMillis() - start;
        long derivStart = System.currentTimeMillis();
        
        ShipRole role = message.getRole();
        ShipDerivedOutputs outputs;
        
        boolean incremental = shouldUseIncrementalPath(context, role);
        
        if (incremental) {
            outputs = dispatchIncrementalForTarget(context, context.message());
        } else {
            outputs = dispatchFull(context);
        }
        
        long derivTime = System.currentTimeMillis() - derivStart;
        long assemStart = System.currentTimeMillis();
        
        RiskDispatchSnapshot snapshot = buildRiskSnapshot(context, outputs, true);
        if (snapshot == null) {
            return;
        }
        
        long assemTime = System.currentTimeMillis() - assemStart;
        long pubStart = System.currentTimeMillis();

        publishRiskSnapshot(snapshot);
        
        int targetCount = Math.max(0, context.allShips().size() - (context.hasOwnShip() ? 1 : 0));
        long pubTime = System.currentTimeMillis() - pubStart;
        log.info("Dispatcher Time: Total={}ms, Prep={}ms, Deriv={}ms (incr={}), Assemble={}ms, Publish={}ms, Targets={}, Role={}",
            System.currentTimeMillis() - start, prepTime, derivTime, incremental, assemTime, pubTime, targetCount,
            context.message() == null ? ShipRole.UNKNOWN : context.message().getRole());
            
        logTargetCpa(context, outputs.cpaResults());
    }
    
    
    public void refreshAfterCleanup() {
        ShipDispatchContext context = new ShipDispatchContext(
                null, shipStateStore.getOwnShip(), shipStateStore.getAll(), Set.of());
        if (!context.hasOwnShip()) return;
        ShipDerivedOutputs outputs = dispatchFull(context);
        RiskDispatchSnapshot snapshot = buildRiskSnapshot(context, outputs, false);
        if (snapshot != null) {
            publishRiskSnapshot(snapshot);
        }
    }

    private ShipDerivedOutputs dispatchFull(ShipDispatchContext context) {
        ShipDerivedOutputs outputs = runDerivations(context);
        this.cachedOwnShipDomainResult = outputs.shipDomainResult();
        
        if (!context.hasOwnShip()) {
            return outputs;
        }

        derivedTargetStateStore.clear();
        for (ShipStatus target : context.allShips()) {
            if (target.getId().equals(context.ownShip().getId())) continue;
            TargetRiskAssessment riskAssessment = riskAssessmentEngine.buildTargetAssessment(
                    target.getId(), 
                    outputs.cpaResults().get(target.getId()), 
                    context.ownShip(), target, 
                    this.cachedOwnShipDomainResult, 
                    outputs.cvPredictionResults().get(target.getId()), 
                    outputs.encounterResults().get(target.getId()));
                    
            TargetDerivedSnapshot ts = new TargetDerivedSnapshot(
                target.getId(),
                outputs.cvPredictionResults().get(target.getId()),
                outputs.cpaResults().get(target.getId()),
                outputs.predictedCpaResults().get(target.getId()),
                outputs.encounterResults().get(target.getId()),
                riskAssessment
            );
            derivedTargetStateStore.put(target.getId(), ts);
        }
        return outputs;
    }
    
    private ShipDerivedOutputs dispatchIncrementalForTarget(ShipDispatchContext context, ShipStatus targetShip) {
        String tId = targetShip.getId();
        
        CvPredictionResult pred = cvPredictionEngine.consume(targetShip, shipTrajectoryStore.getHistory(tId));
        CpaTcpaResult cpa = cpaTcpaBatchCalculator.calculateOne(context.ownShip(), targetShip);
        PredictedCpaTcpaResult predictedCpa = predictedCpaTcpaBatchCalculator.calculateOne(context.ownShip(), pred);
        EncounterClassificationResult enc = encounterClassifier.classify(context.ownShip(), targetShip);
        
        TargetRiskAssessment riskAssessment = riskAssessmentEngine.buildTargetAssessment(
                tId, cpa, context.ownShip(), targetShip, this.cachedOwnShipDomainResult, pred, enc);
                
        TargetDerivedSnapshot ts = new TargetDerivedSnapshot(tId, pred, cpa, predictedCpa, enc, riskAssessment);
        derivedTargetStateStore.put(tId, ts);

        // Reconstruct outputs from cache
        Map<String, CvPredictionResult> cvs = new HashMap<>();
        Map<String, CpaTcpaResult> cpas = new HashMap<>();
        Map<String, PredictedCpaTcpaResult> predictedCpas = new HashMap<>();
        Map<String, EncounterClassificationResult> encs = new HashMap<>();
        
        for (ShipStatus s : context.allShips()) {
            if (s.getId().equals(context.ownShip().getId())) continue;
            TargetDerivedSnapshot st = derivedTargetStateStore.get(s.getId());
            if (st != null) {
                cvs.put(s.getId(), st.predictionResult());
                cpas.put(s.getId(), st.cpaResult());
                predictedCpas.put(s.getId(), st.predictedCpaResult());
                encs.put(s.getId(), st.encounterResult());
            }
        }

        return new ShipDerivedOutputs(this.cachedOwnShipDomainResult, cvs, cpas, predictedCpas, encs);
    }

    private ShipDispatchContext prepareContext(ShipStatus message) {
        if (message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, id: {}", message.getId());
            return null;
        }

        ShipStatus prevState = shipStateStore.get(message.getId());
        ShipStatus qualified = kinematicChecker.check(message, prevState);

        if (!shipStateStore.update(qualified)) {
            return null;
        }

        Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();
        removedIds.forEach(shipTrajectoryStore::remove);
        removedIds.forEach(derivedTargetStateStore::remove);
        shipTrajectoryStore.append(qualified);

        return new ShipDispatchContext(
                qualified,
                shipStateStore.getOwnShip(),
                shipStateStore.getAll(),
                removedIds
        );
    }

    private Map<String, CvPredictionResult> batchPredict(String ownShipId, Collection<ShipStatus> allShips) {
        Map<String, CvPredictionResult> results = new HashMap<>();
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
                continue;
            }
            results.put(ship.getId(), cvPredictionEngine.consume(ship, shipTrajectoryStore.getHistory(ship.getId())));
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
        Map<String, PredictedCpaTcpaResult> predictedCpaResults = predictedCpaTcpaBatchCalculator.calculateAll(
                context.ownShip(),
                cvPredictionResults
        );

        Map<String, EncounterClassificationResult> encounterResults =
                batchClassify(context.ownShip(), context.allShips());

        return new ShipDerivedOutputs(
                shipDomainResult,
                cvPredictionResults,
                cpaResults,
                predictedCpaResults,
                encounterResults
        );
    }

    private RiskDispatchSnapshot buildRiskSnapshot(
            ShipDispatchContext context,
            ShipDerivedOutputs outputs,
            boolean triggerExplanations
    ) {
        if (!context.hasOwnShip()) {
            String incomingId = context.message() != null ? context.message().getId() : "<refresh>";
            log.debug("Skipping RiskObject broadcast until ownShip is available, incoming id={}", incomingId);
            return null;
        }

        RiskAssessmentResult riskResult = buildRiskAssessmentResult(context, outputs);
        ownShipPositionHolder.update(context.ownShip());
        EnvironmentRefreshResult environmentRefresh = environmentContextService.refresh(
                EnvironmentUpdateReason.OWN_SHIP_ENV_REEVALUATED);

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                outputs.predictedCpaResults(),
                riskResult,
                outputs.shipDomainResult(),
                outputs.cvPredictionResults(),
                outputs.encounterResults(),
                environmentRefresh.snapshot().version()
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
                environmentRefresh,
                triggerExplanations
        );
    }

    void publishRiskSnapshot(RiskDispatchSnapshot snapshot) {
        EnvironmentRefreshResult environmentRefresh = snapshot.environmentRefresh();
        if (environmentRefresh != null && environmentRefresh.shouldPublish()) {
            riskStreamPublisher.publishEnvironmentUpdate(
                    environmentRefresh.snapshot(),
                    environmentRefresh.reason(),
                    environmentRefresh.changedFields()
            );
        }
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

    private boolean shouldUseIncrementalPath(ShipDispatchContext context, ShipRole role) {
        if (role != ShipRole.TARGET_SHIP || !context.hasOwnShip() || cachedOwnShipDomainResult == null) {
            return false;
        }
        if (!context.removedTargetIds().isEmpty()) {
            return false;
        }
        return hasCompleteDerivedCache(context);
    }

    private boolean hasCompleteDerivedCache(ShipDispatchContext context) {
        int trackedTargetCount = 0;
        for (ShipStatus ship : context.allShips()) {
            if (ship == null || ship.getId() == null || Objects.equals(ship.getId(), context.ownShip().getId())) {
                continue;
            }
            trackedTargetCount++;
            if (derivedTargetStateStore.get(ship.getId()) == null) {
                return false;
            }
        }
        return derivedTargetStateStore.getAll().size() == trackedTargetCount;
    }

    private RiskAssessmentResult buildRiskAssessmentResult(
            ShipDispatchContext context,
            ShipDerivedOutputs outputs
    ) {
        RiskAssessmentResult cachedResult = rebuildRiskAssessmentFromCache(context);
        if (cachedResult != null) {
            return cachedResult;
        }

        return riskAssessmentEngine.consume(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                outputs.shipDomainResult(),
                outputs.cvPredictionResults(),
                outputs.encounterResults()
        );
    }

    private RiskAssessmentResult rebuildRiskAssessmentFromCache(ShipDispatchContext context) {
        if (!context.hasOwnShip()) {
            return null;
        }

        Map<String, TargetRiskAssessment> assessments = new LinkedHashMap<>();
        for (ShipStatus ship : context.allShips()) {
            if (ship == null || ship.getId() == null || ship.getId().equals(context.ownShip().getId())) {
                continue;
            }
            TargetDerivedSnapshot snapshot = derivedTargetStateStore.get(ship.getId());
            if (snapshot == null || snapshot.riskAssessment() == null) {
                return null;
            }
            assessments.put(ship.getId(), snapshot.riskAssessment());
        }

        return RiskAssessmentResult.builder()
                .targetAssessments(assessments)
                .build();
    }


    private void logTargetCpa(ShipDispatchContext context, Map<String, CpaTcpaResult> cpaResults) {
        if (context.message().getRole() != ShipRole.TARGET_SHIP || !cpaResults.containsKey(context.message().getId())) {
            return;
        }

        CpaTcpaResult cpaResult = cpaResults.get(context.message().getId());
        if (cpaResult == null) {
            return;
        }
        log.debug("CPA={}m, TCPA={}s, target={}",
                String.format("%.1f", cpaResult.getCpaDistance()),
                String.format("%.1f", cpaResult.getTcpaTime()),
                cpaResult.getTargetMmsi());
    }
}
