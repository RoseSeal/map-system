package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.risk.config.EncounterProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRiskContextAssemblerTest {

    private final LlmRiskContextAssembler assembler = new LlmRiskContextAssembler(new EncounterClassifier(new EncounterProperties()));

    @Test
    void assembleCalculatesCurrentDistanceFromShipPositions() {
        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        ShipStatus target = ship("target-1", ShipRole.TARGET_SHIP, 120.1000, 30.1000);

        LlmRiskContext context = assembler.assemble(
                ownShip,
                List.of(ownShip, target),
                java.util.Map.of(),
                null
        );

        assertThat(context.getTargets()).hasSize(1);
        assertThat(context.getTargets().get(0).getTargetId()).isEqualTo("target-1");
        assertThat(context.getTargets().get(0).getCurrentDistanceNm()).isGreaterThan(0.0);
        assertThat(context.getTargets().get(0).getDcpaNm()).isEqualTo(0.0);
        assertThat(context.getTargets().get(0).getTcpaSec()).isEqualTo(0.0);
        assertThat(context.getTargets().get(0).getEncounterType()).isNotNull();
    }

    @Test
    void assembleReturnsPositiveDistanceForKnownTarget() {
        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        ShipStatus target = ship("target-1", ShipRole.TARGET_SHIP, 120.1000, 30.1000);

        LlmRiskContext context = assembler.assemble(
                ownShip,
                List.of(ownShip, target),
                java.util.Map.of(),
                null
        );

        assertThat(context.getTargets()).hasSize(1);
        assertThat(context.getTargets().get(0).getCurrentDistanceNm()).isGreaterThan(0.0);
    }

    @Test
    void assembleCalculatesRelativeBearingUsingHeadingFallback() {
        // trueBearing(own->target) is 0 (North)
        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        ShipStatus target = ship("target-1", ShipRole.TARGET_SHIP, 120.0000, 30.1000);
        // own Heading is 180 (South), COG is 90 (East)
        ownShip.setHeading(180.0);
        ownShip.setCog(90.0);

        LlmRiskContext context = assembler.assemble(
                ownShip,
                List.of(ownShip, target),
                java.util.Map.of(),
                null
        );

        assertThat(context.getTargets()).hasSize(1);
        // relBearing = (0 - 180 + 360) % 360 = 180 (Target is directly behind ship body)
        assertThat(context.getTargets().get(0).getRelativeBearingDeg()).isCloseTo(180.0, within(5.0));
    }

    @Test
    void assemblePopulatesRiskScoreAndDomainPenetrationFromAssessment() {
        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        ShipStatus target = ship("target-1", ShipRole.TARGET_SHIP, 120.1000, 30.1000);

        TargetRiskAssessment assessment = TargetRiskAssessment.builder()
                .targetId("target-1")
                .riskLevel("WARNING")
                .riskScore(0.78)
                .domainPenetration(0.41)
                .build();
        RiskAssessmentResult riskResult = RiskAssessmentResult.builder()
                .targetAssessments(Map.of("target-1", assessment))
                .build();

        LlmRiskContext context = assembler.assemble(
                ownShip,
                List.of(ownShip, target),
                java.util.Map.of(),
                riskResult
        );

        assertThat(context.getTargets()).hasSize(1);
        assertThat(context.getTargets().get(0).getRiskScore()).isEqualTo(0.78);
        assertThat(context.getTargets().get(0).getDomainPenetration()).isEqualTo(0.41);
    }

    private ShipStatus ship(String id, ShipRole role, double longitude, double latitude) {
        return ShipStatus.builder()
                .id(id)
                .role(role)
                .longitude(longitude)
                .latitude(latitude)
                .sog(10.0)
                .cog(90.0)
                .build();
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
