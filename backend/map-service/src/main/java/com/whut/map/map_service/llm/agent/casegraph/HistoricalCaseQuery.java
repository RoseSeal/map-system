package com.whut.map.map_service.llm.agent.casegraph;

import com.whut.map.map_service.llm.agent.graph.VisibilityCondition;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;
import com.whut.map.map_service.shared.domain.RiskLevel;

public record HistoricalCaseQuery(
        EncounterType encounterType,
        OwnShipRole ownShipRole,
        VisibilityCondition visibilityCondition,
        RiskLevel riskLevel,
        String waterArea,
        String ownShipType,
        String targetShipType,
        int targetCount,
        String queryText,
        CaseQueryMode mode,
        Integer topK
) {}
