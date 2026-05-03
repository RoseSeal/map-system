package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.risk.config.EncounterProperties;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.risk.engine.encounter.EncounterRoleResolver;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
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

    @Test
    void weatherContextFilledFromFreshFogSnapshot() {
        WeatherContextHolder holder = new WeatherContextHolder();
        WeatherAlertProperties alertProps = new WeatherAlertProperties();
        LlmRiskContextAssembler weatherAssembler = new LlmRiskContextAssembler(
                new EncounterClassifier(new EncounterProperties()),
                new EncounterRoleResolver(),
                holder,
                new RegionalWeatherResolver(),
                alertProps
        );

        holder.update(new WeatherContext("FOG", 0.8, null, null, null, null, null), List.of());

        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        LlmRiskContext context = weatherAssembler.assemble(ownShip, List.of(ownShip), Map.of(), null);

        assertThat(context.getWeather()).isNotNull();
        assertThat(context.getWeather().getWeatherCode()).isEqualTo("FOG");
        assertThat(context.getWeather().getVisibilityNm()).isEqualTo(0.8);
        assertThat(context.getWeather().getActiveAlerts()).contains("LOW_VISIBILITY");
    }

    @Test
    void weatherContextIsNullWhenHolderHasNoSnapshot() {
        WeatherContextHolder holder = new WeatherContextHolder();
        WeatherAlertProperties alertProps = new WeatherAlertProperties();
        LlmRiskContextAssembler weatherAssembler = new LlmRiskContextAssembler(
                new EncounterClassifier(new EncounterProperties()),
                new EncounterRoleResolver(),
                holder,
                new RegionalWeatherResolver(),
                alertProps
        );

        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        LlmRiskContext context = weatherAssembler.assemble(ownShip, List.of(ownShip), Map.of(), null);

        assertThat(context.getWeather()).isNull();
    }

    @Test
    void weatherContextSourceZoneIdSetWhenZoneMatched() {
        WeatherContextHolder holder = new WeatherContextHolder();
        WeatherAlertProperties alertProps = new WeatherAlertProperties();
        LlmRiskContextAssembler weatherAssembler = new LlmRiskContextAssembler(
                new EncounterClassifier(new EncounterProperties()),
                new EncounterRoleResolver(),
                holder,
                new RegionalWeatherResolver(),
                alertProps
        );

        WeatherContext globalCtx = new WeatherContext("CLEAR", null, null, null, null, null, null);
        List<List<Double>> ring = List.of(
                List.of(119.0, 29.0), List.of(121.0, 29.0),
                List.of(121.0, 31.0), List.of(119.0, 31.0), List.of(119.0, 29.0)
        );
        WeatherZoneContext zone = new WeatherZoneContext(
                "fog-bank-east", "FOG", 0.8, null, null, null, null, null,
                new WeatherZoneContext.ZoneGeometry("Polygon", List.of(ring))
        );
        holder.update(globalCtx, List.of(zone));

        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        LlmRiskContext context = weatherAssembler.assemble(ownShip, List.of(ownShip), Map.of(), null);

        assertThat(context.getWeather()).isNotNull();
        assertThat(context.getWeather().getSourceZoneId()).isEqualTo("fog-bank-east");
        assertThat(context.getWeather().getWeatherCode()).isEqualTo("FOG");
    }

    @Test
    void weatherContextFallsBackToGlobalWhenZoneNotMatched() {
        WeatherContextHolder holder = new WeatherContextHolder();
        WeatherAlertProperties alertProps = new WeatherAlertProperties();
        LlmRiskContextAssembler weatherAssembler = new LlmRiskContextAssembler(
                new EncounterClassifier(new EncounterProperties()),
                new EncounterRoleResolver(),
                holder,
                new RegionalWeatherResolver(),
                alertProps
        );

        WeatherContext globalCtx = new WeatherContext("RAIN", null, null, null, null, null, null);
        List<List<Double>> ring = List.of(
                List.of(130.0, 40.0), List.of(132.0, 40.0),
                List.of(132.0, 42.0), List.of(130.0, 42.0), List.of(130.0, 40.0)
        );
        WeatherZoneContext zone = new WeatherZoneContext(
                "distant-zone", "FOG", 0.5, null, null, null, null, null,
                new WeatherZoneContext.ZoneGeometry("Polygon", List.of(ring))
        );
        holder.update(globalCtx, List.of(zone));

        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        LlmRiskContext context = weatherAssembler.assemble(ownShip, List.of(ownShip), Map.of(), null);

        assertThat(context.getWeather()).isNotNull();
        assertThat(context.getWeather().getSourceZoneId()).isNull();
        assertThat(context.getWeather().getWeatherCode()).isEqualTo("RAIN");
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
