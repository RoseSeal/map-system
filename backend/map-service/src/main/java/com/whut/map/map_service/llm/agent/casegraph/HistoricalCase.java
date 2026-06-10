package com.whut.map.map_service.llm.agent.casegraph;

import java.util.List;

public record HistoricalCase(
        String caseId,
        String title,
        double relevance,
        String waterArea,
        String visibility,
        String ownShipRole,
        String encounterType,
        String riskLevel,
        String targetSummary,
        List<String> colregsRules,
        String outcome,
        String actionDigest,
        String lesson
) {}
