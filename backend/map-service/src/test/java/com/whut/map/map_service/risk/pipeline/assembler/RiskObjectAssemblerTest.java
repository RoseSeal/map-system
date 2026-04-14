package com.whut.map.map_service.risk.pipeline.assembler;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskVisualizationAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.TargetAssembler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskObjectAssemblerTest {

    private final RiskObjectAssembler assembler = new RiskObjectAssembler(
            new RiskObjectMetaAssembler(new RiskObjectMetaProperties()),
            new OwnShipAssembler(),
            new TargetAssembler(new RiskVisualizationAssembler())
    );

    @Test
    void assembleRiskObjectAveragesTargetRiskConfidenceIntoTrustFactor() {
        ShipStatus ownShip = ownShip();
        RiskAssessmentResult riskResult = RiskAssessmentResult.builder()
                .targetAssessments(Map.of(
                        "target-1", TargetRiskAssessment.builder().targetId("target-1").riskConfidence(0.2).build(),
                        "target-2", TargetRiskAssessment.builder().targetId("target-2").riskConfidence(0.8).build()
                ))
                .build();

        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip,
                List.of(ownShip),
                Map.of(),
                riskResult,
                domainResult(),
                Map.of(),
                Map.of()
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 0.5);
    }

    @Test
    void assembleRiskObjectFallsBackToZeroTrustFactorWithoutAssessments() {
        ShipStatus ownShip = ownShip();

        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip,
                List.of(ownShip),
                Map.of(),
                RiskAssessmentResult.empty(),
                domainResult(),
                Map.of(),
                Map.of()
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 0.0);
    }

    @Test
    void assembleRiskObjectFallsBackToZeroTrustFactorWhenAllAssessmentsAreInvalid() {
        ShipStatus ownShip = ownShip();
        RiskAssessmentResult riskResult = RiskAssessmentResult.builder()
                .targetAssessments(Map.of(
                        "target-1", TargetRiskAssessment.builder().targetId("target-1").riskConfidence(Double.NaN).build()
                ))
                .build();

        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip,
                List.of(ownShip),
                Map.of(),
                riskResult,
                domainResult(),
                Map.of(),
                Map.of()
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 0.0);
    }

    private ShipStatus ownShip() {
        return ShipStatus.builder()
                .id("own-1")
                .role(ShipRole.OWN_SHIP)
                .longitude(120.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .build();
    }

    private ShipDomainResult domainResult() {
        return ShipDomainResult.builder()
                .foreNm(0.5)
                .aftNm(0.1)
                .portNm(0.2)
                .stbdNm(0.2)
                .shapeType(ShipDomainResult.SHAPE_ELLIPSE)
                .build();
    }
}
