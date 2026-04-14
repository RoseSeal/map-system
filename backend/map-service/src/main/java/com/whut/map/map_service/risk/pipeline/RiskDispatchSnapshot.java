package com.whut.map.map_service.risk.pipeline;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;

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
