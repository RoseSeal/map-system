package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.assembler.LlmRiskContextAssembler;
import com.whut.map.map_service.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.engine.collision.CpaTcpaEngine;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.service.llm.LlmExplanationService;
import com.whut.map.map_service.service.llm.LlmTriggerService;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.websocket.WebSocketService;
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
    private final WebSocketService aisWebSocketService;
    private final RiskAssessmentEngine riskAssessmentEngine;
    private final RiskObjectAssembler riskObjectAssembler;
    private final LlmRiskContextAssembler llmRiskContextAssembler;
    private final LlmTriggerService llmTriggerService;
    private final ShipStateStore shipStateStore;

    public ShipDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaEngine cpaTcpaEngine,
            WebSocketService aisWebSocketService,
            RiskAssessmentEngine riskAssessmentEngine,
            RiskObjectAssembler riskObjectAssembler,
            LlmRiskContextAssembler llmRiskContextAssembler,
            LlmTriggerService llmTriggerService,
            ShipStateStore shipStateStore

    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaEngine = cpaTcpaEngine;
        this.aisWebSocketService = aisWebSocketService;
        this.riskAssessmentEngine = riskAssessmentEngine;
        this.riskObjectAssembler = riskObjectAssembler;
        this.llmRiskContextAssembler = llmRiskContextAssembler;
        this.llmTriggerService = llmTriggerService;
        this.shipStateStore = shipStateStore;
    }

    public void dispatch(ShipStatus message) {
        // 1) Ignore messages with unknown role because they are not processable in this pipeline.
        if (message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, id: {}", message.getId());
            return;
        }

        // 2) Update state with the latest message; discard outdated messages.
        if (!shipStateStore.update(message)) {
            return;
        }

        // 3) Initialize optional engine outputs used later in risk object assembly.
        ShipDomainResult shipDomainResult = null;
        CvPredictionResult cvPredictionResult = null;
        ShipStatus ownShip = shipStateStore.getOwnShip();

        // 4) Run role-specific engines for the incoming message.
        if (message.getRole() == ShipRole.OWN_SHIP) {
            shipDomainResult = shipDomainEngine.consume(message);
        }
        if (message.getRole() == ShipRole.TARGET_SHIP) {
            cvPredictionResult = cvPredictionEngine.consume(message);
        }

        // 5) Skip downstream risk/CPA processing until own ship is available.
        if (ownShip == null) {
            log.debug("Skipping RiskObject broadcast until ownShip is available, incoming id={}", message.getId());
            return;
        }

        // 6) Compute CPA/TCPA between own ship and every tracked target ship.
        Map<String, CpaTcpaResult> cpaResults = new LinkedHashMap<>();
        shipStateStore.getAll().values().forEach(ship -> {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                return;
            }
            cpaResults.put(ship.getId(), cpaTcpaEngine.calculate(ownShip, ship));
        });

        // 7) Evaluate risk with current state, CPA/TCPA results, and engine outputs.
        RiskAssessmentResult riskResult = riskAssessmentEngine.consume(
                ownShip,
                shipStateStore.getAll().values(),
                cpaResults,
                shipDomainResult,
                cvPredictionResult
        );

        // 8) Build LLM explanations and assemble the websocket payload.
        Map<String, LlmExplanation> llmExplanations = llmTriggerService.triggerExplanationsIfNeeded(
                llmRiskContextAssembler.assemble(
                        ownShip,
                        shipStateStore.getAll().values(),
                        cpaResults,
                        riskResult
                )
        );

        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                ownShip,
                shipStateStore.getAll().values(),
                cpaResults,
                riskResult,
                llmExplanations,
                shipDomainResult,
                cvPredictionResult
        );
        if (dto != null) {
            aisWebSocketService.sendAisMessage(dto);
        }

        // 9) Emit concise per-target CPA/TCPA log for observability.
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

