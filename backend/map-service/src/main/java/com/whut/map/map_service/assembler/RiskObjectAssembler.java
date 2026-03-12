package com.whut.map.map_service.assembler;

import com.whut.map.map_service.domain.AisMessage;
import com.whut.map.map_service.dto.RiskObjectDto;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class RiskObjectAssembler {
    /**
     * 将AisMessage和各Engine的计算结果组装成RiskObjectDto对象
     * 目前先使用Stub(桩实现)
     */

    public RiskObjectDto assembleRiskObject(AisMessage message) {

        Map<String, Double> position = Map.of(
                "latitude", message.getLatitude(),
                "longitude", message.getLongitude()
        );

        Map<String, Double> dynamics = Map.of(
                "sog", message.getSog(),
                "cog", message.getCog(),
                "heading", message.getHeading()
        );

        return RiskObjectDto.builder()
                .riskObjectId(String.valueOf(message.getMmsi()))
                .timestamp(message.getMsgTime().toInstant().toEpochMilli())
                .position(position)
                .dynamics(dynamics)
                // 以下为Stub数据，后续需要替换为实际计算结果
                .platformHealth("NORMAL")
                .futureTrajectory(null)
                .safetyDomain(null)
                .riskLevel("SAFE")
                .governance(Map.of("mode", "AUTO", "trust_factor", 0.99))
                .cpaMetrics(null)
                .build();
    }
}
