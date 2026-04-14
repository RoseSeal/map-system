package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.RiskConstants;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskVisualizationAssemblerTest {

    private final RiskVisualizationAssembler assembler = new RiskVisualizationAssembler();

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