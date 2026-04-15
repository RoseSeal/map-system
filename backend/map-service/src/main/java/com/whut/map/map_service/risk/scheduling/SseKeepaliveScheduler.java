package com.whut.map.map_service.risk.scheduling;

import com.whut.map.map_service.risk.pipeline.ShipDispatcher;
import com.whut.map.map_service.tracking.store.ShipStateStore;
import com.whut.map.map_service.risk.transport.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SseKeepaliveScheduler {

    private final SseEmitterRegistry sseEmitterRegistry;
    private final ShipStateStore shipStateStore;
    private final ShipDispatcher shipDispatcher;

    @Scheduled(initialDelay = 30000L, fixedRate = 30000L)
    public void sendKeepalive() {
        sseEmitterRegistry.sendKeepalive();
    }

    @Scheduled(initialDelay = 30000L, fixedRate = 30000L)
    public void cleanupExpiredShips() {
        if (!shipStateStore.triggerCleanupIfNeeded().isEmpty()) {
            shipDispatcher.refreshAfterCleanup();
        }
    }
}

