package com.whut.map.map_service.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class WebSocketService {

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    public WebSocketService(WebSocketSessionRegistry sessionRegistry, ObjectMapper objectMapper) {
        this.sessionRegistry = sessionRegistry;
        this.objectMapper = objectMapper;
    }

    public void sendToSession(WebSocketSession session, BackendMessage message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            sessionRegistry.sendToSession(session, jsonMessage);
        } catch (Exception e) {
            log.error("Error serializing WebSocket message of type {}: {}", message.getType(), e.getMessage());
        }
    }

    public void broadcast(BackendMessage message) {
        try {
            String jsonMessage = objectMapper.writeValueAsString(message);
            sessionRegistry.broadcast(jsonMessage);
        } catch (Exception e) {
            log.error("Error serializing WebSocket message of type {}: {}", message.getType(), e.getMessage());
        }
    }
}
