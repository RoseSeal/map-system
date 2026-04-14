package com.whut.map.map_service.risk.event;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;

import java.util.Collection;
import java.util.Map;

public record RiskAssessmentCompletedEvent(
        long snapshotVersion,
        ShipStatus ownShip,
        Collection<ShipStatus> allShips,
        Map<String, CpaTcpaResult> cpaResults,
        RiskAssessmentResult riskResult,
        String riskObjectId,
        boolean triggerExplanations
) {
}
