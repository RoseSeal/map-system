package com.whut.map.map_service.store;

import com.whut.map.map_service.domain.ShipStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Slf4j
public class ShipStateStore {

    private final Map<String, ShipStatus> ships = new ConcurrentHashMap<>();

    public boolean update(ShipStatus ship) {
        if (ship == null || ship.getId() == null) {
            log.warn("Attempted to update ship state with null ship or null ID");
            return false;
        }
        AtomicBoolean updated = new AtomicBoolean(false);
        // 使用compute方法来确保线程安全地更新船舶状态
        ships.compute(ship.getId(), (id, existing) -> {
            if (existing == null) {
                updated.set(true);
                return ship;
            }

            OffsetDateTime existingTime = existing.getMsgTime();
            OffsetDateTime newTime = ship.getMsgTime();

            if (existingTime == null) {
                updated.set(true);
                return ship;
            }
            if (newTime == null) {
                return existing;
            }
            if (!newTime.isBefore(existingTime)) {
                updated.set(true);
                return ship;
            }
            return existing;
        });

        return updated.get();
    }

    public ShipStatus get(String id) {
        return ships.get(id);
    }

    public Map<String, ShipStatus> getAll() {
        return Collections.unmodifiableMap(ships);
    }

    public ShipStatus getOwnShip() {
        return ships.get("ownShip");
    }
}
