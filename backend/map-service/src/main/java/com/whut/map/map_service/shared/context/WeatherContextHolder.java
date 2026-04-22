package com.whut.map.map_service.shared.context;

import com.whut.map.map_service.source.weather.dto.WeatherContext;
import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
public class WeatherContextHolder {

    private volatile Snapshot snapshot;

    public void update(WeatherContext globalContext, List<WeatherZoneContext> zones) {
        if (globalContext == null) {
            return;
        }

        Instant now = Instant.now();
        List<WeatherZoneContext> safeZones = (zones == null) ? Collections.emptyList() : List.copyOf(zones);
        this.snapshot = new Snapshot(globalContext.withUpdatedAt(now), safeZones, now);
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

        return Optional.of(current.globalContext());
    }

    public Optional<Snapshot> getFreshSnapshot(Duration staleThreshold) {
        Snapshot current = snapshot;
        if (current == null || staleThreshold == null) {
            return Optional.empty();
        }

        Duration age = Duration.between(current.updatedAt(), Instant.now());
        if (age.compareTo(staleThreshold) > 0) {
            return Optional.empty();
        }

        return Optional.of(current);
    }

    public List<WeatherZoneContext> getFreshZones(Duration staleThreshold) {
        Snapshot current = snapshot;
        if (current == null || staleThreshold == null) {
            return Collections.emptyList();
        }

        Duration age = Duration.between(current.updatedAt(), Instant.now());
        if (age.compareTo(staleThreshold) > 0) {
            return Collections.emptyList();
        }

        return current.zones();
    }

    public Instant getUpdatedAt() {
        return snapshot == null ? null : snapshot.updatedAt();
    }

    public record Snapshot(WeatherContext globalContext, List<WeatherZoneContext> zones, Instant updatedAt) {}
}
