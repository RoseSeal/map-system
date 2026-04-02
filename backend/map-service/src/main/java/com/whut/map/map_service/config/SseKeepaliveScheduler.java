package com.whut.map.map_service.config;

import com.whut.map.map_service.transport.risk.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SseKeepaliveScheduler {

    private final SseEmitterRegistry sseEmitterRegistry;

    @Scheduled(initialDelay = 30000L, fixedRate = 30000L)
    public void sendKeepalive() {
        sseEmitterRegistry.sendKeepalive();
    }
}
