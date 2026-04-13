package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.util.GeoUtils;
import org.springframework.stereotype.Component;

@Component
public class DomainPenetrationCalculator {

    /**
     * @return penetrationRatio: > 0 表示已侵入，0 表示边界上，< 0 表示域外。
     *         null 如果输入不足。
     */
    public Double calculate(
            ShipStatus ownShip,
            ShipStatus targetShip,
            ShipDomainResult domainResult
    ) {
        if (ownShip == null || targetShip == null || domainResult == null) {
            return null;
        }

        double foreNm = domainResult.getForeNm();
        double aftNm = domainResult.getAftNm();
        double portNm = domainResult.getPortNm();
        double stbdNm = domainResult.getStbdNm();

        if (foreNm <= 0 || aftNm <= 0 || portNm <= 0 || stbdNm <= 0) {
            return null;
        }

        Double heading = ownShip.getHeading();
        if (heading == null) {
            heading = ownShip.getCog();
        }
        if (heading == null || Double.isNaN(heading) || heading < 0 || heading >= 360) {
            return null;
        }

        double[] ownXY = GeoUtils.toXY(ownShip.getLatitude(), ownShip.getLongitude());
        double[] targetXY = GeoUtils.toXY(targetShip.getLatitude(), targetShip.getLongitude());

        double dx = targetXY[0] - ownXY[0];
        double dy = targetXY[1] - ownXY[1];

        double angleRad = Math.toRadians(heading);
        double sin = Math.sin(angleRad);
        double cos = Math.cos(angleRad);

        // body frame:
        // dyBody: fore(+)/aft(-)
        // dxBody: starboard(+)/port(-)
        double dxBody = dx * cos - dy * sin;
        double dyBody = dx * sin + dy * cos;

        double longitudinalR = (dyBody >= 0.0) ? foreNm : aftNm;
        double lateralR = (dxBody >= 0.0) ? stbdNm : portNm;

        double dxNm = GeoUtils.metersToNm(dxBody);
        double dyNm = GeoUtils.metersToNm(dyBody);

        double invLat2 = 1.0 / (lateralR * lateralR);
        double invLon2 = 1.0 / (longitudinalR * longitudinalR);

        double ellipseValue = dxNm * dxNm * invLat2 + dyNm * dyNm * invLon2;
        return 1.0 - ellipseValue;
    }
}
