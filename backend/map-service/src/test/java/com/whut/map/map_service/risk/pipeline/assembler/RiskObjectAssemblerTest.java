package com.whut.map.map_service.risk.pipeline.assembler;

import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.risk.environment.EnvironmentContextService;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.risk.environment.OwnShipPositionHolder;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.OwnShipAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskObjectMetaAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.RiskVisualizationAssembler;
import com.whut.map.map_service.risk.pipeline.assembler.riskobject.TargetAssembler;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskObjectAssemblerTest {

    private static final WeatherAlertProperties WEATHER_ALERT_PROPERTIES = new WeatherAlertProperties();
    private static final RiskObjectMetaProperties RISK_OBJECT_META_PROPERTIES = new RiskObjectMetaProperties();
    private static final HydrologyContextService HYDROLOGY_CONTEXT_SERVICE = mock(HydrologyContextService.class);
    private final OwnShipPositionHolder ownShipPositionHolder = new OwnShipPositionHolder();
    private final EnvironmentContextService environmentContextService = environmentContextService();

    private final RiskObjectAssembler assembler = new RiskObjectAssembler(
            new RiskObjectMetaAssembler(RISK_OBJECT_META_PROPERTIES),
            new OwnShipAssembler(),
            new TargetAssembler(new RiskVisualizationAssembler())
    );

    RiskObjectAssemblerTest() {
        when(HYDROLOGY_CONTEXT_SERVICE.resolve(anyDouble(), anyDouble(), anyDouble())).thenReturn(null);
        environmentContextService.refresh(EnvironmentUpdateReason.OWN_SHIP_ENV_REEVALUATED);
    }

    @Test
    void assembleRiskObjectUsesOwnShipConfidenceForTrustFactor() {
        ShipStatus ownShip = ownShip();
        ownShip.setConfidence(0.7);
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
                Map.of(),
                riskResult,
            domainResult(),
            Map.of(),
            Map.of(),
            1L
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 0.7);
    }

    @Test
    void assembleRiskObjectDefaultsToZeroTrustWhenOwnShipConfidenceIsMissing() {
        ShipStatus ownShip = ownShip();

        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip,
                List.of(ownShip),
                Map.of(),
                Map.of(),
                RiskAssessmentResult.empty(),
            domainResult(),
            Map.of(),
            Map.of(),
            1L
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 0.0);
    }

    @Test
    void assembleRiskObjectClampsOwnShipConfidenceIntoValidRange() {
        ShipStatus ownShip = ownShip();
        ownShip.setConfidence(1.5);

        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip,
                List.of(ownShip),
                Map.of(),
                Map.of(),
                RiskAssessmentResult.empty(),
            domainResult(),
            Map.of(),
            Map.of(),
            1L
        );

        assertThat(riskObject.getGovernance()).containsEntry("trust_factor", 1.0);
    }

    @Test
    void assembleRiskObjectUsesProvidedEnvironmentStateVersion() {
        RiskObjectDto riskObject = assembler.assembleRiskObject(
                ownShip(),
                List.of(ownShip()),
                Map.of(),
                Map.of(),
                RiskAssessmentResult.empty(),
                domainResult(),
                Map.of(),
                Map.of(),
                42L
        );

        assertThat(riskObject.getEnvironmentStateVersion()).isEqualTo(42L);
    }

    private EnvironmentContextService environmentContextService() {
        return new EnvironmentContextService(
                RISK_OBJECT_META_PROPERTIES,
                new WeatherContextHolder(),
                WEATHER_ALERT_PROPERTIES,
                new RegionalWeatherResolver(),
                HYDROLOGY_CONTEXT_SERVICE,
                new SafetyContourStateHolder(RISK_OBJECT_META_PROPERTIES),
                ownShipPositionHolder
        );
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
