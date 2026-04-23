package com.whut.map.map_service.llm.agent;

public sealed interface AgentMessage permits TextAgentMessage, ToolCallAgentMessage, ToolResultAgentMessage {
}
