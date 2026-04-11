package com.whut.map.map_service.config.websocket;

import com.whut.map.map_service.llm.config.WhisperProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final WhisperProperties whisperProperties;

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(whisperProperties.getWebSocketTextMessageSizeLimitBytes());
        container.setMaxBinaryMessageBufferSize(whisperProperties.getWebSocketBinaryMessageSizeLimitBytes());
        container.setAsyncSendTimeout(60_000L);
        return container;
    }
}

