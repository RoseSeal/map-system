package com.whut.map.map_service.dto.websocket;

public enum ChatMessageSource {
    CLIENT("client"),
    SERVER("server");

    private final String value;

    ChatMessageSource(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ChatMessageSource fromValue(String value) {
        if (value == null) {
            return null;
        }

        for (ChatMessageSource source : values()) {
            if (source.value.equalsIgnoreCase(value)) {
                return source;
            }
        }

        return null;
    }
}