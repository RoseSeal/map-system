package com.whut.map.map_service.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.FrontendMessage;
import com.whut.map.map_service.dto.websocket.WebSocketMessageTypes;
import com.whut.map.map_service.service.llm.LlmChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class StreamWebSocketHandler extends TextWebSocketHandler {

    private final WebSocketSessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;
    private final WebSocketService webSocketService;
    private final LlmChatService llmChatService;
    private final BackendMessageFactory backendMessageFactory;
    private final ChatMessageFactory chatMessageFactory;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessionRegistry.register(session);
        log.debug("WebSocket connected: {}", session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("Received message from session {}: {}", session.getId(), payload);

        try {
            FrontendMessage frontendMessage = objectMapper.readValue(payload, FrontendMessage.class);
            routeMessage(session, frontendMessage);
        } catch (Exception e) {
            log.warn("Ignoring invalid WebSocket message from session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void routeMessage(WebSocketSession session, FrontendMessage frontendMessage) {
        String type = frontendMessage.getType();
        if (WebSocketMessageTypes.PING.equals(type)) {
            webSocketService.sendToSession(session, backendMessageFactory.buildPongMessage());
            return;
        }
        if (WebSocketMessageTypes.CHAT.equals(type)) {
            handleChatMessage(session, frontendMessage.getMessage());
            return;
        }

        log.warn("Ignoring unsupported WebSocket message type '{}' from session {}.", type, session.getId());
    }

    private void handleChatMessage(WebSocketSession session, JsonNode messageNode) {
        if (messageNode == null || messageNode.isNull()) {
            webSocketService.sendToSession(session, chatMessageFactory.buildErrorMessage(
                    null,
                    null,
                    ChatErrorCode.INVALID_CHAT_REQUEST,
                    "Chat payload is required."
            ));
            return;
        }

        try {
            FrontendChatPayload chatPayload = objectMapper.treeToValue(messageNode, FrontendChatPayload.class);
            llmChatService.handleChat(session, chatPayload);
        } catch (Exception e) {
            webSocketService.sendToSession(session, chatMessageFactory.buildErrorMessage(
                    readText(messageNode, "sequence_id"),
                    readText(messageNode, "message_id"),
                    ChatErrorCode.INVALID_CHAT_REQUEST,
                    "Chat payload format is invalid."
            ));
            log.warn("Failed to parse chat payload from session {}: {}", session.getId(), e.getMessage());
        }
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field != null && !field.isNull()) {
            return field.asText();
        }
        return null;
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        sessionRegistry.unregister(session);
        log.error("WebSocket transport error in session {}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.unregister(session);
        log.debug("WebSocket connection closed: {}, status: {}", session.getId(), status);
    }
}
