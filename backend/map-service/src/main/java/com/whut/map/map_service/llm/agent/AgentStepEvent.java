package com.whut.map.map_service.llm.agent;

public record AgentStepEvent(
        String stepId,
        String toolName,
        AgentStepStatus status,
        String message
) {}
