package com.whut.map.map_service.llm.agent.graph;

import com.whut.map.map_service.risk.engine.encounter.EncounterType;

public record EncounterSituation(
        EncounterType type,
        String descriptionEn,
        String descriptionZh
) {}
