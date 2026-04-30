package com.whut.map.map_service.llm.agent.graph;

import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.encounter.OwnShipRole;

import java.util.List;

public record Rule(
        String ruleId,
        String ruleNumber,
        String title,
        String part,
        String section,
        String summaryEn,
        String summaryZh,
        String principle,
        String sourceCitation,
        List<EncounterType> applicableSituations,
        List<OwnShipRole> applicableRoles,
        List<String> recommendedActionIds,
        List<String> limitations,
        String fullText
) {}
