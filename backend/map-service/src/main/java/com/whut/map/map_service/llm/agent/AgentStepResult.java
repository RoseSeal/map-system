package com.whut.map.map_service.llm.agent;

public sealed interface AgentStepResult permits ToolCallRequest, FinalText {
}
