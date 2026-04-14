import re

with open("backend/map-service/src/main/java/com/whut/map/map_service/pipeline/ShipDispatcher.java", "r") as f:
    content = f.read()

# Add imports
content = content.replace("import com.whut.map.map_service.store.ShipTrajectoryStore;", 
"""import com.whut.map.map_service.store.ShipTrajectoryStore;
import com.whut.map.map_service.store.DerivedTargetStateStore;
import com.whut.map.map_service.store.TargetDerivedSnapshot;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;""")

# Add fields
content = content.replace("private final ShipKinematicQualityChecker kinematicChecker;",
"""private final ShipKinematicQualityChecker kinematicChecker;
    private final DerivedTargetStateStore derivedTargetStateStore;
    private volatile ShipDomainResult cachedOwnShipDomainResult;""")

# Constructor update
old_cons = """public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            EncounterClassifier encounterClassifier,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore,
            ShipTrajectoryStore shipTrajectoryStore,
            RiskStreamPublisher riskStreamPublisher,
            ApplicationEventPublisher applicationEventPublisher,
            ShipKinematicQualityChecker kinematicChecker

    ) {"""
new_cons = """public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            EncounterClassifier encounterClassifier,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore,
            ShipTrajectoryStore shipTrajectoryStore,
            RiskStreamPublisher riskStreamPublisher,
            ApplicationEventPublisher applicationEventPublisher,
            ShipKinematicQualityChecker kinematicChecker,
            DerivedTargetStateStore derivedTargetStateStore
    ) {"""
content = content.replace(old_cons, new_cons)

cons_body = "this.kinematicChecker = kinematicChecker;"
content = content.replace(cons_body, cons_body + "\n        this.derivedTargetStateStore = derivedTargetStateStore;")

# Update dispatch logic
old_dispatch = """public void dispatch(ShipStatus message) {
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
    }"""
new_dispatch = """public void dispatch(ShipStatus message) {
        long start = System.currentTimeMillis();
        ShipDispatchContext context = prepareContext(message);
        if (context == null) {
            return;
        }

        long prepTime = System.currentTimeMillis() - start;
        long derivStart = System.currentTimeMillis();
        
        ShipRole role = message.getRole();
        ShipDerivedOutputs outputs;
        
        boolean incremental = (role == ShipRole.TARGET_SHIP && context.hasOwnShip() && cachedOwnShipDomainResult != null);
        
        if (incremental) {
            outputs = dispatchIncrementalForTarget(context, message);
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
        
        long pubTime = System.currentTimeMillis() - pubStart;
        log.info("Dispatcher Time: Total={}ms, Prep={}ms, Deriv={}ms (incr={}), Assemble={}ms, Publish={}ms, Targets={}", 
            System.currentTimeMillis() - start, prepTime, derivTime, incremental, assemTime, pubTime, context.allShips().size());
            
        logTargetCpa(context, outputs.cpaResults());
    }
    
    public void refreshAfterCleanup() {
        ShipDispatchContext context = new ShipDispatchContext(
                null, shipStateStore.getOwnShip(), shipStateStore.getAll());
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
        EncounterClassificationResult enc = encounterClassifier.classify(context.ownShip(), targetShip);
        
        TargetRiskAssessment riskAssessment = riskAssessmentEngine.buildTargetAssessment(
                tId, cpa, context.ownShip(), targetShip, this.cachedOwnShipDomainResult, pred, enc);
                
        TargetDerivedSnapshot ts = new TargetDerivedSnapshot(tId, pred, cpa, enc, riskAssessment);
        derivedTargetStateStore.put(tId, ts);
        
        // Reconstruct outputs from cache
        Map<String, CvPredictionResult> cvs = new HashMap<>();
        Map<String, CpaTcpaResult> cpas = new HashMap<>();
        Map<String, EncounterClassificationResult> encs = new HashMap<>();
        
        for (ShipStatus s : context.allShips()) {
            if (s.getId().equals(context.ownShip().getId())) continue;
            TargetDerivedSnapshot st = derivedTargetStateStore.get(s.getId());
            if (st != null) {
                cvs.put(s.getId(), st.predictionResult());
                cpas.put(s.getId(), st.cpaResult());
                encs.put(s.getId(), st.encounterResult());
            }
        }
        
        return new ShipDerivedOutputs(this.cachedOwnShipDomainResult, cvs, cpas, encs);
    }"""
content = content.replace(old_dispatch, new_dispatch)

# cleanup update
old_cleanup = """Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();
        removedIds.forEach(shipTrajectoryStore::remove);
        shipTrajectoryStore.append(qualified);"""
new_cleanup = """Set<String> removedIds = shipStateStore.triggerCleanupIfNeeded();
        removedIds.forEach(shipTrajectoryStore::remove);
        removedIds.forEach(derivedTargetStateStore::remove);
        shipTrajectoryStore.append(qualified);"""
content = content.replace(old_cleanup, new_cleanup)

with open("backend/map-service/src/main/java/com/whut/map/map_service/pipeline/ShipDispatcher.java", "w") as f:
    f.write(content)
