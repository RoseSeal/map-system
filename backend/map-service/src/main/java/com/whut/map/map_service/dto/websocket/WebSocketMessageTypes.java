package com.whut.map.map_service.dto.websocket;

public final class WebSocketMessageTypes {

    public static final String PING = "PING";
    public static final String PONG = "PONG";
    public static final String CHAT = "CHAT";
    public static final String CHAT_TRANSCRIPT = "CHAT_TRANSCRIPT";
    public static final String CHAT_REPLY = "CHAT_REPLY";
    public static final String CHAT_ERROR = "CHAT_ERROR";
    public static final String RISK_UPDATE = "RISK_UPDATE";

    private WebSocketMessageTypes() {
    }
}
