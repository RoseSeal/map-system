package com.whut.map.map_service.dto.websocket;

public enum ChatMessageType {
    PING("PING"),
    PONG("PONG"),
    CHAT("CHAT"),
    SPEECH("SPEECH"),
    CHAT_REPLY("CHAT_REPLY"),
    SPEECH_TRANSCRIPT("SPEECH_TRANSCRIPT"),
    ERROR("ERROR");

    private final String value;

    ChatMessageType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static ChatMessageType fromValue(String value) {
        if (value == null) {
            return null;
        }

        for (ChatMessageType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }

        return null;
    }
}
