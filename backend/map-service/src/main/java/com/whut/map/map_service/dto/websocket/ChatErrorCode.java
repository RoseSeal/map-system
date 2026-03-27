package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatErrorCode {
    INVALID_CHAT_REQUEST("INVALID_CHAT_REQUEST"),
    LLM_TIMEOUT("LLM_TIMEOUT"),
    LLM_REQUEST_FAILED("LLM_REQUEST_FAILED"),
    LLM_DISABLED("LLM_DISABLED");

    private final String value;

    ChatErrorCode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
