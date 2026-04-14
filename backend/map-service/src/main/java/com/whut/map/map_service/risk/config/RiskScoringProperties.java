package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk.scoring")
public class RiskScoringProperties {
    private double geometryWeight = 0.6; // 几何运动因子权重
    private double domainWeight = 0.4;  // 域侵入因子权重
    private double headOnModifier = 1.2;  // 对遇修正系数
    private double crossingModifier = 1.0; // 交叉修正系数
    private double overtakingModifier = 0.8; // 追越修正系数
    private double undefinedModifier = 1.0;  // 未定义会遇类型修正系数（透传）
}
