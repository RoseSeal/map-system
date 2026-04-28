package com.whut.map.map_service.risk.scheduling;

import com.whut.map.map_service.risk.environment.EnvironmentContextService;
import com.whut.map.map_service.risk.environment.EnvironmentRefreshResult;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.risk.pipeline.ShipDispatcher;
import com.whut.map.map_service.tracking.store.ShipStateStore;
import com.whut.map.map_service.risk.transport.SseEmitterRegistry;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
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
    private final EnvironmentContextService environmentContextService;
    private final RiskStreamPublisher riskStreamPublisher;

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

    @Scheduled(initialDelay = 20000L, fixedRateString = "${engine.risk-meta.weather-alert.stale-check-interval-ms:20000}")
    public void publishExpiredWeatherEnvironment() {
        if (!environmentContextService.latestEnvironmentContainsWeather() || environmentContextService.hasFreshWeather()) {
            return;
        }
        EnvironmentRefreshResult refresh = environmentContextService.refresh(EnvironmentUpdateReason.WEATHER_EXPIRED);
        if (refresh.shouldPublish()) {
            riskStreamPublisher.publishEnvironmentUpdate(refresh.snapshot(), refresh.reason(), refresh.changedFields());
        }
    }
}
