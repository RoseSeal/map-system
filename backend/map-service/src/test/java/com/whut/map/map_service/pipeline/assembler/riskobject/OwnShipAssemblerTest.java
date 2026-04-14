package com.whut.map.map_service.pipeline.assembler.riskobject;

import com.whut.map.map_service.domain.QualityFlag;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OwnShipAssemblerTest {

    private final OwnShipAssembler assembler = new OwnShipAssembler();

    @Test
    void assemblesSafetyDomainFromShipDomainResult() {
        ShipStatus ownShip = ShipStatus.builder()
                .id("ownShip")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .build();
            ShipDomainResult domainResult = domainResult();

        Map<String, Object> ownShipData = assembler.assemble(ownShip, domainResult);

        assertThat(ownShipData).containsEntry("id", "ownShip");
        Map<String, Object> safetyDomain = castMap(ownShipData.get("safety_domain"));
        Map<String, Object> dimensions = castMap(safetyDomain.get("dimensions"));
        assertThat(safetyDomain).containsEntry("shape_type", ShipDomainResult.SHAPE_ELLIPSE);
        assertThat(dimensions).containsEntry("fore_nm", 0.8);
        assertThat(dimensions).containsEntry("aft_nm", 0.2);
        assertThat(dimensions).containsEntry("port_nm", 0.3);
        assertThat(dimensions).containsEntry("stbd_nm", 0.4);
    }

    @Test
    void assembleMarksHealthAsDegradedWhenFlagsPresent() {
        ShipStatus ownShip = ShipStatus.builder()
                .id("ownShip")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .qualityFlags(Set.of(QualityFlag.MISSING_HEADING))
                .build();

        Map<String, Object> ownShipData = assembler.assemble(ownShip, domainResult());

        Map<String, Object> platformHealth = castMap(ownShipData.get("platform_health"));
        assertThat(platformHealth).containsEntry("status", "DEGRADED");
        assertThat((String) platformHealth.get("description")).contains("MISSING_HEADING");
    }

    @Test
    void assembleMarksHealthAsNormalWhenFlagsEmpty() {
        ShipStatus ownShip = ShipStatus.builder()
                .id("ownShip")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .qualityFlags(Collections.emptySet())
                .build();

        Map<String, Object> ownShipData = assembler.assemble(ownShip, domainResult());

        Map<String, Object> platformHealth = castMap(ownShipData.get("platform_health"));
        Map<String, Object> futureTrajectory = castMap(ownShipData.get("future_trajectory"));
        assertThat(platformHealth).containsEntry("status", "NORMAL");
        assertThat(platformHealth).containsEntry("description", "");
        assertThat(futureTrajectory).containsEntry("prediction_type", "linear");
    }

    private ShipDomainResult domainResult() {
        return ShipDomainResult.builder()
                .foreNm(0.8)
                .aftNm(0.2)
                .portNm(0.3)
                .stbdNm(0.4)
                .shapeType(ShipDomainResult.SHAPE_ELLIPSE)
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
