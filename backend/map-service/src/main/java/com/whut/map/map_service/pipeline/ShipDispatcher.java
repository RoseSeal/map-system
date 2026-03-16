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

    // 注入
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

    // 分发数据
    public void dispatch(ShipStatus message) {
        /**
         * TODO:
         * 目前实现的顺序分发，后续需要修改为异步分发，
         * 使用线程池或者消息队列来处理不同引擎的消费逻辑，以提高系统的吞吐量和响应速度
         */
        // 对于未知角色的消息，记录日志并跳过处理
        if (message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, MMSI: {}", message.getId());
            return;
        }

        // 更新状态
        if (!shipStateStore.update(message)) {
            return;
        }

        CpaTcpaResult cpaResult = null;
        RiskAssessmentResult riskResult = null;
        ShipDomainResult shipDomainResult = null;
        CvPredictionResult cvPredictionResult = null;

        // 根据船舶角色分发到不同的引擎
        if (message.getRole() == ShipRole.OWN_SHIP) {
            shipDomainResult = shipDomainEngine.consume(message);
        }

        if (message.getRole() == ShipRole.TARGET_SHIP) {
            ShipStatus ownShip = shipStateStore.getOwnShip();
            if (ownShip != null) {
                cpaResult = cpaTcpaEngine.calculate(ownShip, message);
            }
            cvPredictionResult = cvPredictionEngine.consume(message);
        }

        // 所有消息都需要风险评估并发送到WebSocket服务
        riskResult = riskAssessmentEngine.consume(message);
        // 目前是同步流，未来修改为异步后，需要去缓存池中获取各个引擎的计算结果，再进行组装和发送
        RiskObjectDto dto = riskObjectAssembler.assembleRiskObject(
                message,
                cpaResult,
                riskResult,
                shipDomainResult,
                cvPredictionResult
        );
        aisWebSocketService.sendAisMessage(dto);

        // 验证CPA/TCPA计算结果是否正确
        if (cpaResult != null) {
            log.info("CPA={}m, TCPA={}s, target={}",
                    String.format("%.1f", cpaResult.getCpaDistance()),
                    String.format("%.1f", cpaResult.getTcpaTime()),
                    cpaResult.getTargetMmsi());
        } else {
            log.info("CPA/TCPA calculation skipped for MMSI: {}", message.getId());
        }
    }
}