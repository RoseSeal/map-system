package com.whut.map.map_service.llm.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record ToolCallRequest(
        String callId,
        String toolName,
        ObjectNode arguments
) implements AgentStepResult {

    public ToolCallRequest {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(arguments, "arguments must not be null");
    }
}
