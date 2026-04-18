package com.whut.map.map_service.shared.context;

import com.whut.map.map_service.source.weather.dto.WeatherContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class WeatherContextHolder {

    private volatile Snapshot snapshot;

    public void update(WeatherContext context) {
        if (context == null) {
            return;
        }

        Instant now = Instant.now();
        this.snapshot = new Snapshot(context.withUpdatedAt(now), now);
    }

    public Optional<WeatherContext> getFreshContext(Duration staleThreshold) {
        Snapshot current = snapshot;
        if (current == null || staleThreshold == null) {
            return Optional.empty();
        }

        Duration age = Duration.between(current.updatedAt(), Instant.now());
        if (age.compareTo(staleThreshold) > 0) {
            return Optional.empty();
        }

        return Optional.of(current.context());
    }

    public Instant getUpdatedAt() {
        return snapshot == null ? null : snapshot.updatedAt();
    }

    record Snapshot(WeatherContext context, Instant updatedAt) {
    }
}
