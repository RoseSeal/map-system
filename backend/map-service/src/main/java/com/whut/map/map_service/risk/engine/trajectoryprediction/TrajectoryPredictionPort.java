package com.whut.map.map_service.risk.engine.trajectoryprediction;

import com.whut.map.map_service.shared.domain.ShipStatus;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public interface TrajectoryPredictionPort {

    CvPredictionResult consume(ShipStatus message, List<ShipStatus> history);

    default Map<String, CvPredictionResult> consumeAll(
            String ownShipId,
            Collection<ShipStatus> allShips,
            Function<String, List<ShipStatus>> historyProvider
    ) {
        Map<String, CvPredictionResult> results = new HashMap<>();
        if (allShips == null) {
            return results;
        }
        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShipId)) {
                continue;
            }
            List<ShipStatus> history = historyProvider == null ? List.of() : historyProvider.apply(ship.getId());
            results.put(ship.getId(), consume(ship, history));
        }
        return results;
    }
}
