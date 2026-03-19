package com.whut.map.map_service.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OwnShipAssembler {

    public Map<String, Object> assemble(ShipStatus ownShip, ShipDomainResult domainResult, CvPredictionResult cvResult) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", ownShip.getLongitude());
        position.put("lat", ownShip.getLatitude());

        Map<String, Object> dynamics = new LinkedHashMap<>();
        dynamics.put("sog", ownShip.getSog());
        dynamics.put("cog", ownShip.getCog());
        dynamics.put("hdg", normalizedHeading(ownShip));
        dynamics.put("rot", 0.0);

        Map<String, Object> platformHealth = new LinkedHashMap<>();
        platformHealth.put("status", "NORMAL");
        platformHealth.put("description", "");

        Map<String, Object> futureTrajectory = new LinkedHashMap<>();
        futureTrajectory.put("prediction_type", "linear");

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("fore_nm", 0.5);
        dimensions.put("aft_nm", 0.1);
        dimensions.put("port_nm", 0.2);
        dimensions.put("stbd_nm", 0.2);

        Map<String, Object> safetyDomain = new LinkedHashMap<>();
        safetyDomain.put("shape_type", "ellipse");
        safetyDomain.put("dimensions", dimensions);

        Map<String, Object> ownShipData = new LinkedHashMap<>();
        ownShipData.put("id", ownShip.getId());
        ownShipData.put("position", position);
        ownShipData.put("dynamics", dynamics);
        ownShipData.put("platform_health", platformHealth);
        ownShipData.put("future_trajectory", futureTrajectory);
        ownShipData.put("safety_domain", safetyDomain);
        return ownShipData;
    }

    private double normalizedHeading(ShipStatus shipStatus) {
        return shipStatus.getHeading() == null ? shipStatus.getCog() : shipStatus.getHeading();
    }
}
