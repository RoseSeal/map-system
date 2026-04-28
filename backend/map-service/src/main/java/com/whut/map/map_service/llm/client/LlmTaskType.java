package com.whut.map.map_service.llm.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum LlmTaskType {
    EXPLANATION("explanation"),
    CHAT("chat"),
    AGENT("agent");

    private final String value;

    LlmTaskType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static LlmTaskType fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(taskType -> taskType.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported LLM task type: " + value));
    }

    @Override
    public String toString() {
        return value;
    }
}
