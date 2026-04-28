package com.whut.map.map_service.risk.environment;

import com.whut.map.map_service.shared.domain.ShipStatus;
import org.springframework.stereotype.Component;

@Component
public class OwnShipPositionHolder {

    private volatile OwnShipPosition currentPosition;

    public void update(ShipStatus ownShip) {
        if (ownShip == null) {
            return;
        }
        currentPosition = new OwnShipPosition(ownShip.getLatitude(), ownShip.getLongitude());
    }

    public OwnShipPosition getCurrentPosition() {
        return currentPosition;
    }

    public record OwnShipPosition(double latitude, double longitude) {
    }
}
