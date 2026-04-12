package com.whut.map.map_service.store;

import com.whut.map.map_service.domain.ShipStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ShipTrajectoryStore {
    private static final int MAX_HISTORY = 20;

    private final ConcurrentHashMap<String, List<ShipStatus>> trajectories =
            new ConcurrentHashMap<>();

    public void append(ShipStatus ship) {
        if (ship == null || ship.getId() == null) {
            return;
        }
        ShipStatus snapshot = snapshotOf(ship);
        trajectories.compute(ship.getId(), (id, existing) -> {
            List<ShipStatus> next = existing == null
                    ? new ArrayList<>()
                    : new ArrayList<>(existing);
            next.add(snapshot);
            if (next.size() > MAX_HISTORY) {
                next.remove(0);
            }
            return Collections.unmodifiableList(next);
        });
    }

    public List<ShipStatus> getHistory(String shipId) {
        List<ShipStatus> list = trajectories.get(shipId);
        return list == null ? Collections.emptyList() : list;
    }

    public void remove(String shipId) {
        trajectories.remove(shipId);
    }

    private ShipStatus snapshotOf(ShipStatus ship) {
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
                .build();
    }
}
