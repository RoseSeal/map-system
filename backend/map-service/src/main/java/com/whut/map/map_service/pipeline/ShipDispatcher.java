package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.*;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.websocket.AisWebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class ShipDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaEngine cpaTcpaEngine;
    private final AisWebSocketService aisWebSocketService;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final ShipStateStore shipStateStore;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaEngine cpaTcpaEngine,
            AisWebSocketService aisWebSocketService,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            ShipStateStore shipStateStore
    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaEngine = cpaTcpaEngine;
        this.aisWebSocketService = aisWebSocketService;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.shipStateStore = shipStateStore;
    }

    public void dispatch(ShipStatus message) {
        if (message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, id: {}", message.getId());
            return;
        }

        if (!shipStateStore.update(message)) {
            return;
        }

        RiskAssessmentResult riskResult = null;
        ShipDomainResult shipDomainResult = null;
        CvPredictionResult cvPredictionResult = null;
        ShipStatus ownShip = shipStateStore.getOwnShip();
        Map<String, CpaTcpaResult> cpaResults = new LinkedHashMap<>();

        if (message.getRole() == ShipRole.OWN_SHIP) {
            shipDomainResult = shipDomainEngine.consume(message);
        }

        if (message.getRole() == ShipRole.TARGET_SHIP) {
            cvPredictionResult = cvPredictionEngine.consume(message);
        }

        if (ownShip != null) {
            shipStateStore.getAll().values().forEach(ship -> {
                if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                    return;
                }
                cpaResults.put(ship.getId(), cpaTcpaEngine.calculate(ownShip, ship));
            });
        }

        riskResult = riskAssessmentEngine.consume(message);

        if (ownShip == null) {
            log.debug("Skipping RiskObject broadcast until ownShip is available, incoming id={}", message.getId());
            return;
        }

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                ownShip,
                shipStateStore.getAll().values(),
                cpaResults,
                riskResult,
                shipDomainResult,
                cvPredictionResult
        );
        if (dto != null) {
            aisWebSocketService.sendAisMessage(dto);
        }

        if (message.getRole() == ShipRole.TARGET_SHIP && cpaResults.containsKey(message.getId())) {
            CpaTcpaResult cpaResult = cpaResults.get(message.getId());
            log.info("CPA={}m, TCPA={}s, target={}",
                    String.format("%.1f", cpaResult.getCpaDistance()),
                    String.format("%.1f", cpaResult.getTcpaTime()),
                    cpaResult.getTargetMmsi());
        } else {
            log.info("CPA/TCPA calculation skipped for id: {}", message.getId());
        }
    }
}
