package com.whut.map.map_service.llm.agent.graph;

public record ManeuverAction(
        String actionId,
        ManeuverActionType type,
        String descriptionEn,
        String descriptionZh,
        String rationale
) {}
