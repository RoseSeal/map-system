package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "risk.hydrology.penalty")
public class HydrologyRiskProperties {

    private boolean enabled = false;
    private double shoalMaxPenaltyScore = 0.12;
    private double obstructionMaxPenaltyScore = 0.08;
    private double shoalInfluenceNm = 0.5;
    private double obstructionInfluenceNm = 0.5;
}
