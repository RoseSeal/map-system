package com.whut.map.map_service.llm.agent.graph;

import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.shared.domain.RiskLevel;

public record RegulatoryQuery(
        EncounterType encounterType,
        OwnShipRole ownShipRole,
        RiskLevel riskLevel,
        VisibilityCondition visibilityCondition
) {}
