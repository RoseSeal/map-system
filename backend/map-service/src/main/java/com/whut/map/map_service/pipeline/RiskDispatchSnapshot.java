package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;

import java.util.Collection;
import java.util.Map;

record RiskDispatchSnapshot(
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskAssessmentResult,
        RiskObjectDto riskObject,
        boolean triggerExplanations
) {
}
