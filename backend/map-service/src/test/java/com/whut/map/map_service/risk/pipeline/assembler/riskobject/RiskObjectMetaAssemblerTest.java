package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskObjectMetaAssemblerTest {

    private static final WeatherAlertProperties WEATHER_ALERT_PROPERTIES = new WeatherAlertProperties();

    @Test
    void buildGovernanceUsesConfiguredModeAndProvidedTrustFactor() {
        RiskObjectMetaProperties properties = new RiskObjectMetaProperties();
        properties.setGovernanceMode("manual");
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(
                properties,
                new WeatherContextHolder(),
                WEATHER_ALERT_PROPERTIES,
                new RegionalWeatherResolver()
        );

        Map<String, Object> governance = assembler.buildGovernance(0.42);

        assertThat(governance).containsEntry("mode", "manual");
        assertThat(governance).containsEntry("trust_factor", 0.42);
    }

    @Test
    void buildEnvironmentContextUsesEffectiveSafetyContour() {
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(
                new RiskObjectMetaProperties(),
                new WeatherContextHolder(),
                WEATHER_ALERT_PROPERTIES,
                new RegionalWeatherResolver()
        );

        ShipStatus ownShip = ShipStatus.builder().id("OWN").latitude(0.0).longitude(0.0).build();
        Map<String, Object> environmentContext = assembler.buildEnvironmentContext(ownShip, 12.5, null);

        assertThat(environmentContext).containsEntry("safety_contour_val", 12.5);
        assertThat(environmentContext).containsEntry("active_alerts", java.util.List.of());
        assertThat(environmentContext).containsEntry("weather", null);
        assertThat(environmentContext).containsEntry("weather_zones", null);
        assertThat(environmentContext).containsEntry("hydrology", null);
    }

    @Test
    void buildEnvironmentContextIncludesHydrologyPayloadAndAlerts() {
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(
                new RiskObjectMetaProperties(),
                new WeatherContextHolder(),
                WEATHER_ALERT_PROPERTIES,
                new RegionalWeatherResolver()
        );

        HydrologyContext hydrologyContext = new HydrologyContext(
                8.3,
                0.0,
                new NearestObstructionSummary("WRECK", 0.71, 37)
        );

        ShipStatus ownShip = ShipStatus.builder().id("OWN").latitude(0.0).longitude(0.0).build();
        Map<String, Object> environmentContext = assembler.buildEnvironmentContext(ownShip, 10.0, hydrologyContext);

        assertThat(environmentContext.get("active_alerts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("SHOAL_PROXIMITY", "OBSTRUCTION_NEARBY");
        assertThat(environmentContext.get("hydrology"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("own_ship_min_depth_m", 8.3)
                .containsEntry("nearest_shoal_nm", 0.0)
                .extractingByKey("nearest_obstruction")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "WRECK")
                .containsEntry("distance_nm", 0.71)
                .containsEntry("bearing_deg", 37);
    }

    @Test
    void buildEnvironmentContextCombinesWeatherAndHydrologyAlerts() {
        WeatherContextHolder weatherContextHolder = new WeatherContextHolder();
        weatherContextHolder.update(new com.whut.map.map_service.source.weather.dto.WeatherContext(
                "FOG",
                0.8,
                0.0,
                null,
                null,
                2,
                Instant.now()
        ), java.util.List.of());
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(
                new RiskObjectMetaProperties(),
                weatherContextHolder,
                WEATHER_ALERT_PROPERTIES,
                new RegionalWeatherResolver()
        );

        HydrologyContext hydrologyContext = new HydrologyContext(null, null, null);

        ShipStatus ownShip = ShipStatus.builder().id("OWN").latitude(0.0).longitude(0.0).build();
        Map<String, Object> environmentContext = assembler.buildEnvironmentContext(ownShip, 10.0, hydrologyContext);

        assertThat(environmentContext.get("active_alerts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("LOW_VISIBILITY", "DEPTH_DATA_MISSING");
    }
}
