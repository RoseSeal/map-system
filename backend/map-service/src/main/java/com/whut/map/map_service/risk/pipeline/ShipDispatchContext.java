package com.whut.map.map_service.risk.pipeline;

import com.whut.map.map_service.shared.domain.ShipStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

record ShipDispatchContext(
        ShipStatus message,
        ShipStatus ownShip,
        Map<String, ShipStatus> trackedShips,
        Set<String> removedTargetIds
) {
    ShipDispatchContext {
        removedTargetIds = removedTargetIds == null ? Collections.emptySet() : removedTargetIds;
    }

    Collection<ShipStatus> allShips() {
        return trackedShips.values();
    }

    boolean hasOwnShip() {
        return ownShip != null;
    }
}
