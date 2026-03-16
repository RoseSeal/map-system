package com.whut.map.map_service.engine.safety;

import com.whut.map.map_service.domain.ShipStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShipDomainEngine {

    private ShipDomainResult calculate() {
        // 1. 获取相关数据
        // 2. 计算船舶域
        // 3. 将结果发送到WebSocket服务
        return null;
    }

    public ShipDomainResult consume(ShipStatus message) {
        // 1. 从数据源获取数据
        log.debug("Received AIS message for ship domain calculation, MMSI: {}", message.getId());
        // 2. 调用计算方法
        return calculate();
    }
}
