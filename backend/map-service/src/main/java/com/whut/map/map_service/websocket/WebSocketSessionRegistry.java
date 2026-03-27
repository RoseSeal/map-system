package com.whut.map.map_service.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketSessionRegistry {

    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public void register(WebSocketSession session) {
        sessions.add(session);
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    public void sendToSession(WebSocketSession session, String messagePayload) {
        if (session == null || !session.isOpen()) {
            log.debug("Skipping send because the target WebSocket session is null or closed.");
            return;
        }

        try {
            session.sendMessage(new TextMessage(messagePayload));
        } catch (Exception e) {
            log.error("Error sending message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    public void broadcast(String messagePayload) {
        if (sessions.isEmpty()) {
            log.debug("No active WebSocket sessions to broadcast message.");
            return;
        }

        log.debug("Broadcasting message to WebSocket, payload: {}", messagePayload);
        TextMessage message = new TextMessage(messagePayload);
        sessions.forEach(session -> {
            if (!session.isOpen()) {
                return;
            }
            try {
                session.sendMessage(message);
            } catch (Exception e) {
                log.error("Error broadcasting message to session {}: {}", session.getId(), e.getMessage());
            }
        });
    }
}
