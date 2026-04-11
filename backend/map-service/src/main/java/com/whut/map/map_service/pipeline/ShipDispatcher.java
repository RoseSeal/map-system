package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.assembler.LlmRiskContextAssembler;
import com.whut.map.map_service.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.service.llm.LlmErrorCode;
import com.whut.map.map_service.service.llm.LlmTriggerService;
import com.whut.map.map_service.service.llm.RiskContextHolder;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import com.whut.map.map_service.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ShipDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaBatchCalculator cpaTcpaBatchCalculator;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final LlmRiskContextAssembler llmRiskContextAssembler;
    private final LlmTriggerService llmTriggerService;
    private final RiskContextHolder riskContextHolder;
    private final ShipStateStore shipStateStore;
    private final RiskStreamPublisher riskStreamPublisher;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaBatchCalculator cpaTcpaBatchCalculator,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            LlmRiskContextAssembler llmRiskContextAssembler,
            LlmTriggerService llmTriggerService,
            RiskContextHolder riskContextHolder,
            ShipStateStore shipStateStore,
            RiskStreamPublisher riskStreamPublisher

    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaBatchCalculator = cpaTcpaBatchCalculator;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.llmRiskContextAssembler = llmRiskContextAssembler;
        this.llmTriggerService = llmTriggerService;
        this.riskContextHolder = riskContextHolder;
        this.shipStateStore = shipStateStore;
        this.riskStreamPublisher = riskStreamPublisher;
    }

    public void dispatch(ShipStatus message) {
        ShipDispatchContext context = prepareContext(message);
        if (context == null) {
            return;
        }

        ShipDerivedOutputs outputs = runDerivations(context);
        RiskDispatchSnapshot snapshot = buildRiskSnapshot(context, outputs);
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

    private RiskDispatchSnapshot buildRiskSnapshot(ShipDispatchContext context, ShipDerivedOutputs outputs) {
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
                Collections.emptyMap(),
                outputs.shipDomainResult(),
                outputs.cvPredictionResult()
        );
        if (dto == null) {
            return null;
        }

        Map<String, Double> currentDistancesNm = buildCurrentDistancesNm(
                context.ownShip(),
                context.allShips()
        );

        LlmRiskContext llmContext = llmRiskContextAssembler.assemble(
                context.ownShip(),
                context.allShips(),
                currentDistancesNm,
                outputs.cpaResults(),
                riskResult
        );
        return new RiskDispatchSnapshot(riskResult, dto, llmContext);
    }

    void publishRiskSnapshot(RiskDispatchSnapshot snapshot) {
        riskContextHolder.update(snapshot.llmContext());
        riskStreamPublisher.publishRiskUpdate(snapshot.riskObject());
        String riskObjectId = snapshot.riskObject().getRiskObjectId();
        llmTriggerService.triggerExplanationsIfNeeded(
                snapshot.llmContext(),
                explanation -> riskStreamPublisher.publishExplanation(explanation, riskObjectId),
                (target, error) -> {
                    ChatErrorCode errorCode = mapToProtocolErrorCode(error == null ? null : error.errorCode());
                    riskStreamPublisher.publishError(
                            errorCode,
                            error == null ? "LLM explanation request failed." : error.errorMessage(),
                            target == null ? null : target.getTargetId()
                    );
                }
        );
    }

    Map<String, Double> buildCurrentDistancesNm(ShipStatus ownShip, Collection<ShipStatus> allShips) {
        Map<String, Double> currentDistancesNm = new HashMap<>();
        if (ownShip == null || allShips == null) {
            return currentDistancesNm;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }

            double distanceMeters = GeoUtils.distanceMetersByXY(
                    ownShip.getLatitude(),
                    ownShip.getLongitude(),
                    ship.getLatitude(),
                    ship.getLongitude()
            );
            currentDistancesNm.put(ship.getId(), GeoUtils.metersToNm(distanceMeters));
        }

        return currentDistancesNm;
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
                ownShip, allShips, cpaResults, riskResult,
                Collections.emptyMap(), null, null);
        if (dto == null) {
            return;
        }

        Map<String, Double> currentDistancesNm = buildCurrentDistancesNm(ownShip, allShips);
        LlmRiskContext llmContext = llmRiskContextAssembler.assemble(
                ownShip, allShips, currentDistancesNm, cpaResults, riskResult);

        riskContextHolder.update(llmContext);
        riskStreamPublisher.publishRiskUpdate(dto);
        log.debug("Published refreshed risk snapshot after cleanup, targets={}", allShips.size() - 1);
    }

    private ChatErrorCode mapToProtocolErrorCode(LlmErrorCode errorCode) {
        if (errorCode == null) {
            return ChatErrorCode.LLM_REQUEST_FAILED;
        }
        return switch (errorCode) {
            case LLM_TIMEOUT -> ChatErrorCode.LLM_TIMEOUT;
            case LLM_FAILED -> ChatErrorCode.LLM_REQUEST_FAILED;
            case LLM_DISABLED -> ChatErrorCode.LLM_DISABLED;
            case CONVERSATION_BUSY -> ChatErrorCode.CONVERSATION_BUSY;
            case TRANSCRIPTION_FAILED -> ChatErrorCode.TRANSCRIPTION_FAILED;
            case TRANSCRIPTION_TIMEOUT -> ChatErrorCode.TRANSCRIPTION_TIMEOUT;
        };
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
