package com.whut.map.map_service.engine.safety;

import com.whut.map.map_service.config.properties.ShipDomainProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.AisProtocolConstants;
import com.whut.map.map_service.util.MathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ShipDomainEngine {
    private final ShipDomainProperties shipDomainProperties;

    private ShipDomainResult calculate(ShipStatus shipStatus) {
        double referenceSpeedKn = shipDomainProperties.getReferenceSpeedKn();
        if (Double.isNaN(referenceSpeedKn) || referenceSpeedKn <= 0.0) {
            referenceSpeedKn = 8.0;
        }

        double sog = shipStatus.getSog();
        if (!AisProtocolConstants.isValidSog(sog)) {
            sog = referenceSpeedKn;
        }

        double speedFactor = MathUtils.clamp(
                sog / referenceSpeedKn,
                shipDomainProperties.getMinSpeedFactor(),
                shipDomainProperties.getMaxSpeedFactor()
        );

        return ShipDomainResult.builder()
                .foreNm(shipDomainProperties.getBaseForeNm() * speedFactor)
                .aftNm(shipDomainProperties.getBaseAftNm() * speedFactor)
                .portNm(shipDomainProperties.getBasePortNm() * speedFactor)
                .stbdNm(shipDomainProperties.getBaseStbdNm() * speedFactor)
                .shapeType(ShipDomainResult.SHAPE_ELLIPSE)
                .build();
    }

    public ShipDomainResult consume(ShipStatus message) {
        log.debug("Received AIS message for ship domain calculation, MMSI: {}", message.getId());
        return calculate(message);
    }
}
