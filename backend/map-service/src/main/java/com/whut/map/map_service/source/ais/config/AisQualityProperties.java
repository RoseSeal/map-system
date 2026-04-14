package com.whut.map.map_service.source.ais.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.ais-quality")
public class AisQualityProperties {
    private double positionJumpSpeedMultiplier = 3.0; // 位置跳变容忍倍数 (K)
    private double positionJumpMinSpeedKn = 0.5;      // SOG 下限保护值，防止静止船误触发 (kn)
    private double sogJumpThresholdKn = 10.0;         // 航速突变阈值 (kn/帧)
    private double cogJumpThresholdDeg = 90.0;        // 航向突变阈值 (度/帧)，使用圆弧差值
}
