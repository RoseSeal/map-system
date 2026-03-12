package com.whut.map.map_service.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;


import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StreamWebSocketHandler extends TextWebSocketHandler {

    // 不使用static, 因为Spring会管理这个组件的生命周期，确保它是单例的
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // 连接建立后，可以在这里进行一些初始化操作
        // 将新建立的连接加入到会话列表中
        sessions.add(session);
        log.debug("WebSocket connected: {}", session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 处理接收到的消息
        String payload = message.getPayload();
        log.debug("Received message from session {}: {}", session.getId(), payload);

        // 发送 PONG 响应以保持连接活跃
        // 直接用字符串匹配来检测 PING 消息，避免 JSON 解析的开销
        if(payload.contains("\"type\":\"PING\"") || payload.contains("\"type\": \"PING\"")) {
            try {
                session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
                log.debug("Sent PONG response to session {}", session.getId());
            } catch (Exception e) {
                log.error("Error sending PONG response to session {}: {}", session.getId(), e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        // 处理传输错误
        // 移除发生错误的连接，避免内存泄漏
        sessions.remove(session);
        log.error("WebSocket transport error in session {}: {}", session.getId(), exception.getMessage());

    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        // 连接关闭后，可以在这里进行一些清理操作
        // 清理掉已经关闭的连接，避免内存泄漏
        sessions.remove(session);
        log.debug("WebSocket connection closed: {}, status: {}", session.getId(), status);
    }

    public void broadcastMessage(String messagePayload) {
        if(sessions.isEmpty()) {
            log.debug("No active WebSocket sessions to broadcast message.");
            return; // 无人连接时直接返回，避免不必要的日志输出
        }

        // 将数据发送到WebSocket客户端
        log.debug("Broadcasting message to WebSocket, payload: {}", messagePayload);
        TextMessage message = new TextMessage(messagePayload);
        sessions.forEach(session -> {
            if (session.isOpen()) {
                try {
                    session.sendMessage(message);
                } catch (Exception e) {
                    log.error("Error broadcasting message to session {}: {}", session.getId(), e.getMessage());
                }
            }
        });
    }
}
