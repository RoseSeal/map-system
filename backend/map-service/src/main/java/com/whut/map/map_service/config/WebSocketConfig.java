package com.whut.map.map_service.config;

import com.whut.map.map_service.websocket.ChatWebSocketHandler;
import com.whut.map.map_service.websocket.ProtocolPaths;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final WhisperProperties whisperProperties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, ProtocolPaths.CHAT_SOCKET)
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(whisperProperties.getWebSocketTextMessageSizeLimitBytes());
        container.setMaxBinaryMessageBufferSize(whisperProperties.getWebSocketBinaryMessageSizeLimitBytes());
        container.setAsyncSendTimeout(60_000L);
        return container;
    }
}
