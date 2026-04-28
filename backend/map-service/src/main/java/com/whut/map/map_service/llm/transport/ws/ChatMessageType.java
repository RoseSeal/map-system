package com.whut.map.map_service.llm.transport.ws;

public enum ChatMessageType {
    PING("PING"),
    PONG("PONG"),
    CAPABILITY("CAPABILITY"),
    CHAT("CHAT"),
    SPEECH("SPEECH"),
    CLEAR_HISTORY("CLEAR_HISTORY"),
    SET_LLM_PROVIDER_SELECTION("SET_LLM_PROVIDER_SELECTION"),
    CHAT_REPLY("CHAT_REPLY"),
    AGENT_STEP("AGENT_STEP"),
    SPEECH_TRANSCRIPT("SPEECH_TRANSCRIPT"),
    CLEAR_HISTORY_ACK("CLEAR_HISTORY_ACK"),
    LLM_PROVIDER_SELECTION("LLM_PROVIDER_SELECTION"),
    CLEAR_EXPIRED_EXPLANATIONS("CLEAR_EXPIRED_EXPLANATIONS"),
    EXPIRED_EXPLANATIONS_CLEARED("EXPIRED_EXPLANATIONS_CLEARED"),
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
