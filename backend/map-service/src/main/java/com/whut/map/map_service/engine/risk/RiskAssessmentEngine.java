package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.domain.ShipStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RiskAssessmentEngine {

        private RiskAssessmentResult calculate() {
            // 1. 获取相关数据
            // 2. 计算风险评估
            // 3. 将结果发送到WebSocket服务
            return null;
        }

        public RiskAssessmentResult consume(ShipStatus message) {
            // 1. 从数据源获取数据
            log.debug("Received AIS message for risk assessment, MMSI: {}", message.getId());
            // 2. 调用计算方法
            return calculate();
        }

}
