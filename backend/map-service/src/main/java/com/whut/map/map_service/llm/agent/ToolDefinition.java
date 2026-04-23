package com.whut.map.map_service.llm.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public record ToolDefinition(
        String name,
        String description,
        ObjectNode parametersJsonSchema
) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        Objects.requireNonNull(parametersJsonSchema, "parametersJsonSchema must not be null");
    }
}
