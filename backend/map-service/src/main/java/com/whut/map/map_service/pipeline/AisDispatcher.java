package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.domain.*;
import com.whut.map.map_service.engine.CpaTcpaEngine;
import com.whut.map.map_service.engine.CvPredictionEngine;
import com.whut.map.map_service.engine.safetyDomain.RiskAssessmentEngine;
import com.whut.map.map_service.engine.safetyDomain.ShipDomainEngine;
import com.whut.map.map_service.websocket.AisWebSocketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AisDispatcher {

    private final ShipDomainEngine shipDomainEngine;
    private final CvPredictionEngine cvPredictionEngine;
    private final CpaTcpaEngine cpaTcpaEngine;
    private final AisWebSocketService aisWebSocketService;
    private final RiskAssessmentEngine riskAssessmentEngine;

    public AisDispatcher(
            ShipDomainEngine shipDomainEngine,
            CvPredictionEngine cvPredictionEngine,
            CpaTcpaEngine cpaTcpaEngine,
            AisWebSocketService aisWebSocketService,
            RiskAssessmentEngine riskAssessmentEngine
    ) {
        this.shipDomainEngine = shipDomainEngine;
        this.cvPredictionEngine = cvPredictionEngine;
        this.cpaTcpaEngine = cpaTcpaEngine;
        this.aisWebSocketService = aisWebSocketService;
        this.riskAssessmentEngine = riskAssessmentEngine;
    }

    // 分发数据
    public void dispatch(AisMessage message) {
        /**
         * TODO:
         * 目前实现的顺序分发，后续需要修改为异步分发，
         * 使用线程池或者消息队列来处理不同引擎的消费逻辑，以提高系统的吞吐量和响应速度
         */

        // 根据船舶角色分发到不同的引擎
        if(message.getRole() == ShipRole.OWN_SHIP) {
            shipDomainEngine.consume(message);
        }
        if(message.getRole() == ShipRole.TARGET_SHIP) {
            riskAssessmentEngine.consume(message);
        }

        // 对于未知角色的消息，记录日志并跳过处理
        if(message.getRole() == ShipRole.UNKNOWN) {
            log.debug("Received AIS message with unknown ship role, MMSI: {}", message.getMmsi());
            return;
        }

        // 所有消息都需要进行CV预测，CPA/TCPA计算以及发送到WebSocket服务
        cvPredictionEngine.consume(message);
        cpaTcpaEngine.consume(message);
        aisWebSocketService.sentAisMessage(message);
    }
}