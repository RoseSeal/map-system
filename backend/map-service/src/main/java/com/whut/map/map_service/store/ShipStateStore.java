package com.whut.map.map_service.store;

import com.whut.map.map_service.config.properties.ShipStateProperties;
import com.whut.map.map_service.domain.ShipStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Slf4j
@RequiredArgsConstructor
public class ShipStateStore {

    private final ShipStateProperties shipStateProperties;
    private final Map<String, TrackedShip> ships = new ConcurrentHashMap<>();
    private final AtomicLong nextCleanupAtMillis = new AtomicLong(0L);

    private record TrackedShip(ShipStatus ship, OffsetDateTime lastSeenAt) {
    }

    public boolean update(ShipStatus ship) {
        if (ship == null || ship.getId() == null) {
            log.warn("Attempted to update ship state with null ship or null ID");
            return false;
        }

        OffsetDateTime now = OffsetDateTime.now();
        AtomicBoolean updated = new AtomicBoolean(false);
        // 使用 compute 方法来确保线程安全地更新船舶状态
        ships.compute(ship.getId(), (id, existing) -> {
            if (existing == null) {
                updated.set(true);
                return new TrackedShip(snapshotOf(ship), now);
            }

            OffsetDateTime existingTime = existing.ship().getMsgTime();
            OffsetDateTime newTime = ship.getMsgTime();

            if (existingTime == null) {
                updated.set(true);
                return new TrackedShip(snapshotOf(ship), now);
            }
            if (newTime == null) {
                return existing;
            }
            if (newTime.isEqual(existingTime)) {
                return existing;
            }
            if (newTime.isAfter(existingTime)) {
                updated.set(true);
                return new TrackedShip(snapshotOf(ship), now);
            }
            return existing;
        });

        return updated.get();
    }

    public ShipStatus get(String id) {
        TrackedShip trackedShip = ships.get(id);
        return trackedShip == null ? null : snapshotOf(trackedShip.ship());
    }

    public Map<String, ShipStatus> getAll() {
        Map<String, ShipStatus> snapshot = new LinkedHashMap<>();
        ships.forEach((id, trackedShip) -> {
            if (trackedShip != null && trackedShip.ship() != null) {
                snapshot.put(id, snapshotOf(trackedShip.ship()));
            }
        });
        return Collections.unmodifiableMap(snapshot);
    }

    public ShipStatus getOwnShip() {
        TrackedShip trackedShip = ships.get("ownShip");
        return trackedShip == null ? null : snapshotOf(trackedShip.ship());
    }

    private ShipStatus snapshotOf(ShipStatus ship) {
        if (ship == null) {
            return null;
        }

        return ShipStatus.builder()
                .id(ship.getId())
                .role(ship.getRole())
                .longitude(ship.getLongitude())
                .latitude(ship.getLatitude())
                .sog(ship.getSog())
                .cog(ship.getCog())
                .heading(ship.getHeading())
                .msgTime(ship.getMsgTime())
                .confidence(ship.getConfidence())
                .qualityFlags(copyQualityFlags(ship.getQualityFlags()))
                .build();
    }

    private java.util.Set<com.whut.map.map_service.domain.QualityFlag> copyQualityFlags(
            java.util.Set<com.whut.map.map_service.domain.QualityFlag> qualityFlags
    ) {
        if (qualityFlags == null) {
            return null;
        }
        if (qualityFlags.isEmpty()) {
            return Collections.emptySet();
        }
        return EnumSet.copyOf(qualityFlags);
    }

    java.util.Set<String> purgeExpiredShips(OffsetDateTime referenceTime) {
        if (referenceTime == null) {
            return Collections.emptySet();
        }

        long expireAfterSeconds = Math.max(1L, shipStateProperties.getExpireAfterSeconds());
        OffsetDateTime cutoffTime = referenceTime.minusSeconds(expireAfterSeconds);
        ArrayList<String> removedShipIds = new ArrayList<>();

        ships.forEach((id, trackedShip) -> {
            if ("ownShip".equals(id)) {
                return;
            }
            if (trackedShip == null || trackedShip.lastSeenAt() == null || !trackedShip.lastSeenAt().isBefore(cutoffTime)) {
                return;
            }

            if (ships.remove(id, trackedShip)) {
                removedShipIds.add(id);
            }
        });

        if (!removedShipIds.isEmpty()) {
            log.debug("Removed {} expired ship states older than {}s: {}", removedShipIds.size(), expireAfterSeconds, removedShipIds);
        }
        return Collections.unmodifiableSet(new java.util.HashSet<>(removedShipIds));
    }

    public java.util.Set<String> triggerCleanupIfNeeded() {
        long cleanupIntervalMillis = Math.max(1L, shipStateProperties.getCleanupIntervalSeconds()) * 1000L;
        long now = System.currentTimeMillis();
        long nextCleanupAt = nextCleanupAtMillis.get();
        if (now < nextCleanupAt) {
            return Collections.emptySet();
        }
        if (!nextCleanupAtMillis.compareAndSet(nextCleanupAt, now + cleanupIntervalMillis)) {
            return Collections.emptySet();
        }

        return purgeExpiredShips(OffsetDateTime.now());
    }
}
