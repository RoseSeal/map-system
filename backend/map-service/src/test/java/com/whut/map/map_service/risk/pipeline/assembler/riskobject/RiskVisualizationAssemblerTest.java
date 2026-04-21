package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.RiskConstants;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskVisualizationAssemblerTest {

    private final RiskVisualizationAssembler assembler = new RiskVisualizationAssembler();

    @Test
    void buildGraphicCpaLineProjectsBothShipsToTcpaPositions() {
        ShipStatus ownShip = ShipStatus.builder()
                .longitude(120.0)
                .latitude(30.0)
                .sog(10.0)
                .cog(90.0)
                .build();
        ShipStatus targetShip = ShipStatus.builder()
                .longitude(120.02)
                .latitude(30.01)
                .sog(12.0)
                .cog(180.0)
                .build();
        TargetRiskAssessment assessment = TargetRiskAssessment.builder()
                .approaching(true)
                .tcpaSeconds(120.0)
                .build();

        Map<String, Object> result = assembler.buildGraphicCpaLine(ownShip, targetShip, assessment);

        double[] expectedOwn = GeoUtils.displace(30.0, 120.0, GeoUtils.toVelocity(10.0, 90.0)[0] * 120.0, GeoUtils.toVelocity(10.0, 90.0)[1] * 120.0);
        double[] expectedTarget = GeoUtils.displace(30.01, 120.02, GeoUtils.toVelocity(12.0, 180.0)[0] * 120.0, GeoUtils.toVelocity(12.0, 180.0)[1] * 120.0);

        assertThat(result).isNotNull();
        List<Double> ownPos = (List<Double>) result.get("own_pos");
        List<Double> targetPos = (List<Double>) result.get("target_pos");
        assertThat(ownPos).hasSize(2);
        assertThat(targetPos).hasSize(2);
        assertThat(ownPos.get(0)).isCloseTo(expectedOwn[1], org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ownPos.get(1)).isCloseTo(expectedOwn[0], org.assertj.core.data.Offset.offset(1e-9));
        assertThat(targetPos.get(0)).isCloseTo(expectedTarget[1], org.assertj.core.data.Offset.offset(1e-9));
        assertThat(targetPos.get(1)).isCloseTo(expectedTarget[0], org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void buildGraphicCpaLineReturnsNullWhenTargetIsNotApproaching() {
        ShipStatus ownShip = ShipStatus.builder().longitude(120.0).latitude(30.0).sog(10.0).cog(90.0).build();
        ShipStatus targetShip = ShipStatus.builder().longitude(120.02).latitude(30.01).sog(12.0).cog(180.0).build();
        TargetRiskAssessment assessment = TargetRiskAssessment.builder()
                .approaching(false)
                .tcpaSeconds(120.0)
                .build();

        assertThat(assembler.buildGraphicCpaLine(ownShip, targetShip, assessment)).isNull();
    }

    @Test
    void buildOztSectorReturnsNullOutsideHighRiskLevels() {
        ShipStatus target = ShipStatus.builder().cog(90.0).build();

        assertThat(assembler.buildOztSector(target, RiskConstants.SAFE, EncounterType.CROSSING)).isNull();
        assertThat(assembler.buildOztSector(target, RiskConstants.CAUTION, EncounterType.CROSSING)).isNull();
    }

    @Test
    void buildOztSectorUsesHeadOnHalfAngle() {
        ShipStatus target = ShipStatus.builder().cog(90.0).build();

        Map<String, Object> result = assembler.buildOztSector(target, RiskConstants.WARNING, EncounterType.HEAD_ON);

        assertThat(result).containsEntry("start_angle_deg", 70.0);
        assertThat(result).containsEntry("end_angle_deg", 110.0);
        assertThat(result).containsEntry("is_active", true);
    }

    @Test
    void buildOztSectorUsesOvertakingHalfAngle() {
        ShipStatus target = ShipStatus.builder().cog(90.0).build();

        Map<String, Object> result = assembler.buildOztSector(target, RiskConstants.WARNING, EncounterType.OVERTAKING);

        assertThat(result).containsEntry("start_angle_deg", 82.0);
        assertThat(result).containsEntry("end_angle_deg", 98.0);
    }

    @Test
    void buildOztSectorUsesCrossingHalfAngle() {
        ShipStatus target = ShipStatus.builder().cog(90.0).build();

        Map<String, Object> result = assembler.buildOztSector(target, RiskConstants.ALARM, EncounterType.CROSSING);

        assertThat(result).containsEntry("start_angle_deg", 78.0);
        assertThat(result).containsEntry("end_angle_deg", 102.0);
    }

    @Test
    void buildOztSectorFallsBackToDefaultHalfAngle() {
        ShipStatus target = ShipStatus.builder().cog(90.0).build();

        Map<String, Object> result = assembler.buildOztSector(target, RiskConstants.WARNING, null);

        assertThat(result).containsEntry("start_angle_deg", 80.0);
        assertThat(result).containsEntry("end_angle_deg", 100.0);
    }
}
