package com.whut.map.map_service.assembler;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class RiskObjectAssembler {
    /**
     * 将AisMessage和各Engine的计算结果组装成RiskObjectDto对象
     * 目前先使用Stub(桩实现)
     */

    public RiskObjectDto assembleRiskObject(
            ShipStatus message,
            CpaTcpaResult cpaResult,
            RiskAssessmentResult riskResult,
            ShipDomainResult domainResult,
            CvPredictionResult cvResult
    ) {

        // 1. 组装本船的实体数据 (Entity State)
        Map<String, Object> ownShipData = new HashMap<>();
        ownShipData.put("position", Map.of("lon", message.getLongitude(), "lat", message.getLatitude()));
        ownShipData.put("dynamics", Map.of("sog", message.getSog(), "cog", message.getCog(), "hdg", message.getHeading(), "rot", 0.0));
        ownShipData.put("platform_health", "NORMAL");
        ownShipData.put("governance", Map.of("mode", "AUTO", "trust_factor", 0.99));

        // 把高阶属性作为本船的特征放入 ownShip，并用空集合防止前端 filter/map 崩溃
        ownShipData.put("safety_domain", java.util.Collections.emptyList());
        ownShipData.put("future_trajectory", java.util.Collections.emptyList());

        // 2. 组装上帝视角的全局快照 (World State)
        return RiskObjectDto.builder()
                // 全局快照 ID 可以用本船 id 甚至是一个随机 UUID 代替
                .riskObjectId(message.getId())
                .timestamp(message.getMsgTime().toInstant().toEpochMilli())
                .governance(Map.of("mode", "AUTO", "trust_factor", 0.99))
                // 把本船数据塞入 own_ship 字段
                .ownShip(ownShipData)
                // 对于集合/数组类元素，永远不能传 null，至少要传一个空集合/数组，否则前端会报错
                // .targets(null)
                .targets(java.util.Collections.emptyList())
                .build();
    }
}
