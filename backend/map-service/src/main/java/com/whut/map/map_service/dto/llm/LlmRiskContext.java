package com.whut.map.map_service.dto.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LlmRiskContext {
    private LlmRiskOwnShipContext ownShip;
    private List<LlmRiskTargetContext> targets;
}
