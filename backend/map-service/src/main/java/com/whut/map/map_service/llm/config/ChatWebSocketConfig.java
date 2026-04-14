package com.whut.map.map_service.llm.config;

import com.whut.map.map_service.llm.transport.ws.ChatWebSocketHandler;
import com.whut.map.map_service.shared.transport.protocol.ProtocolPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class ChatWebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, ProtocolPaths.CHAT_SOCKET)
                .setAllowedOriginPatterns("*");
    }
}
