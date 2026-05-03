package com.whut.map.map_service.llm.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
// 风险上下文，包含本船和目标船的相关信息，用于生成风险解释
public class LlmRiskContext {
    private LlmRiskOwnShipContext ownShip;
    private List<LlmRiskTargetContext> targets;
    private LlmRiskWeatherContext weather;
}
