package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum SpeechMode {
    DIRECT("direct"),
    PREVIEW("preview");

    private final String value;

    SpeechMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static SpeechMode fromValue(String value) {
        for (SpeechMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown SpeechMode value: " + value);
    }
}
