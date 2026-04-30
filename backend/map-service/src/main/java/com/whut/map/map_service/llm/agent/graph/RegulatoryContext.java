package com.whut.map.map_service.llm.agent.graph;

import java.util.List;

public record RegulatoryContext(
        List<Rule> rules,
        List<ManeuverAction> recommendedActions,
        List<String> limitations
) {}
