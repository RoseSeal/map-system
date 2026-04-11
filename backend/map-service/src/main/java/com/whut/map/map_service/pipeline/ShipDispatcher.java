package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.riskstream.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.dto.riskstream.RiskFrame;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

@Slf4j
@Component
public class ShipDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaBatchCalculator cpaTcpaBatchCalculator;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final ShipStateStore shipStateStore;
    private final RiskStreamPublisher riskStreamPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore,
            RiskStreamPublisher riskStreamPublisher,
            ApplicationEventPublisher applicationEventPublisher

    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaBatchCalculator = cpaTcpaBatchCalculator;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.shipStateStore = shipStateStore;
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

        shipStateStore.triggerCleanupIfNeeded();
        return new ShipDispatchContext(message, shipStateStore.getOwnShip(), shipStateStore.getAll());
    }

    private ShipDerivedOutputs runDerivations(ShipDispatchContext context) {
        ShipDomainResult shipDomainResult = null;
        CvPredictionResult cvPredictionResult = null;

        if (context.message().getRole() == ShipRole.OWN_SHIP) {
            shipDomainResult = shipDomainEngine.consume(context.message());
        }
        if (context.message().getRole() == ShipRole.TARGET_SHIP) {
            cvPredictionResult = cvPredictionEngine.consume(context.message());
        }

        Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(
                context.ownShip(),
                context.allShips()
        );

        return new ShipDerivedOutputs(shipDomainResult, cvPredictionResult, cpaResults);
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
                outputs.cvPredictionResult()
        );

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                context.ownShip(),
                context.allShips(),
                outputs.cpaResults(),
                riskResult,
                outputs.shipDomainResult(),
                outputs.cvPredictionResult()
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

        Map<String, CpaTcpaResult> cpaResults = cpaTcpaBatchCalculator.calculateAll(ownShip, allShips);

        RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
                ownShip, allShips, cpaResults, null, null);

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                ownShip, allShips, cpaResults, riskResult, null, null);
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
