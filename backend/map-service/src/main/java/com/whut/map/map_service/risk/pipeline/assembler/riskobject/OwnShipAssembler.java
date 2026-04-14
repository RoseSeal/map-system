package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.shared.domain.QualityFlag;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.pipeline.assembler.AssemblerProtocolConstants;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class OwnShipAssembler {

    public Map<String, Object> assemble(ShipStatus ownShip, ShipDomainResult domainResult) {
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("lon", ownShip.getLongitude());
        position.put("lat", ownShip.getLatitude());

        Map<String, Object> dynamics = new LinkedHashMap<>();
        dynamics.put("sog", ownShip.getSog());
        dynamics.put("cog", ownShip.getCog());
        dynamics.put("hdg", normalizedHeading(ownShip));
        dynamics.put("rot", 0.0);

        Set<QualityFlag> flags = ownShip.getQualityFlags();
        boolean hasFlags = flags != null && !flags.isEmpty();

        Map<String, Object> platformHealth = new LinkedHashMap<>();
        platformHealth.put("status", hasFlags ? AssemblerProtocolConstants.HEALTH_STATUS_DEGRADED
                : AssemblerProtocolConstants.HEALTH_STATUS_NORMAL);
        platformHealth.put("description", hasFlags
                ? flags.stream().map(QualityFlag::name).sorted().collect(Collectors.joining(", "))
                : "");

        Map<String, Object> futureTrajectory = new LinkedHashMap<>();
        futureTrajectory.put("prediction_type", AssemblerProtocolConstants.PREDICTION_TYPE_LINEAR);

        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("fore_nm", domainResult.getForeNm());
        dimensions.put("aft_nm", domainResult.getAftNm());
        dimensions.put("port_nm", domainResult.getPortNm());
        dimensions.put("stbd_nm", domainResult.getStbdNm());

        Map<String, Object> safetyDomain = new LinkedHashMap<>();
        safetyDomain.put("shape_type", domainResult.getShapeType());
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
