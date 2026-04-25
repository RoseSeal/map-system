package com.whut.map.map_service.llm.service;

import java.util.Locale;

public enum ChatAgentMode {
    CHAT,
    AGENT;

    public static ChatAgentMode fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return CHAT;
        }
        try {
            return ChatAgentMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
