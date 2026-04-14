package com.whut.map.map_service.risk.engine.collision;

import com.whut.map.map_service.shared.domain.ShipStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class CpaTcpaBatchCalculator {

    private final CpaTcpaEngine cpaTcpaEngine;

    public Map<String, CpaTcpaResult> calculateAll(ShipStatus ownShip, Collection<ShipStatus> ships) {
        if (ownShip == null || ships == null) {
            return Collections.emptyMap();
        }

        Map<String, CpaTcpaResult> cpaResults = new LinkedHashMap<>();
        for (ShipStatus ship : ships) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }
            cpaResults.put(ship.getId(), cpaTcpaEngine.calculate(ownShip, ship));
        }
        return cpaResults;
    }

    public CpaTcpaResult calculateOne(ShipStatus ownShip, ShipStatus targetShip) {
        if (ownShip == null || targetShip == null || targetShip.getId() == null || targetShip.getId().equals(ownShip.getId())) {
            return null;
        }
        return cpaTcpaEngine.calculate(ownShip, targetShip);
    }
}
