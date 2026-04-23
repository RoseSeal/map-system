package com.whut.map.map_service.llm.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record ToolResult(
        String callId,
        String toolName,
        ObjectNode payload
) {

    public ToolResult {
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        Objects.requireNonNull(payload, "payload must not be null");
    }
}
