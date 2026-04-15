package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MessageRole {
    USER("user"),
    ASSISTANT("assistant");

    private final String value;

    MessageRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static MessageRole fromValue(String value) {
        for (MessageRole role : values()) {
            if (role.value.equalsIgnoreCase(value)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown MessageRole value: " + value);
    }
}
