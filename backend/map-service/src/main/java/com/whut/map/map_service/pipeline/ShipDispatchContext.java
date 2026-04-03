package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.domain.ShipStatus;

import java.util.Collection;
import java.util.Map;

record ShipDispatchContext(
        ShipStatus message,
        ShipStatus ownShip,
        Map<String, ShipStatus> trackedShips
) {
    Collection<ShipStatus> allShips() {
        return trackedShips.values();
    }

    boolean hasOwnShip() {
        return ownShip != null;
    }
}
