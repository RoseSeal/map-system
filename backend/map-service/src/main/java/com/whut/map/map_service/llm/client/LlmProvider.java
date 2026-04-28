package com.whut.map.map_service.llm.client;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum LlmProvider {
    GEMINI("gemini"),
    ZHIPU("zhipu");

    private final String value;

    LlmProvider(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static LlmProvider fromValue(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(provider -> provider.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported LLM provider: " + value));
    }

    @Override
    public String toString() {
        return value;
    }
}
