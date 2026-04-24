package com.whut.map.map_service.llm.agent.tool;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.ToolCall;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResult;

public interface AgentTool {
    ToolDefinition getDefinition();
    ToolResult execute(ToolCall call, AgentSnapshot snapshot);
}
