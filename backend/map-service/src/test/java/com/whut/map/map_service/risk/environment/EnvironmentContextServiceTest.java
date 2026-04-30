package com.whut.map.map_service.risk.environment;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.risk.config.WeatherRiskProperties;
import com.whut.map.map_service.risk.weather.WeatherRiskAdjustmentEvaluator;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EnvironmentContextServiceTest {

    @Test
    void refreshUsesEffectiveSafetyContour() {
        RiskObjectMetaProperties properties = new RiskObjectMetaProperties();
        SafetyContourStateHolder safetyContourStateHolder = new SafetyContourStateHolder(properties);
        safetyContourStateHolder.updateDepthMeters(12.5);
        EnvironmentContextService service = service(
                properties,
                new WeatherContextHolder(),
                safetyContourStateHolder,
                mock(HydrologyContextService.class),
                new OwnShipPositionHolder()
        );

        EnvironmentRefreshResult result = service.refresh(EnvironmentUpdateReason.SAFETY_CONTOUR_UPDATED);

        assertThat(result.snapshot().environmentContext()).containsEntry("safety_contour_val", 12.5);
        assertThat(result.snapshot().environmentContext()).containsEntry("active_alerts", List.of());
        assertThat(result.snapshot().environmentContext()).containsEntry("weather", null);
        assertThat(result.snapshot().environmentContext()).containsEntry("weather_zones", null);
        assertThat(result.snapshot().environmentContext()).containsEntry("hydrology", null);
    }

    @Test
    void refreshIncludesHydrologyPayloadAndAlerts() {
        HydrologyContextService hydrologyContextService = mock(HydrologyContextService.class);
        when(hydrologyContextService.resolve(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new HydrologyContext(
                        8.3,
                        0.0,
                        new NearestObstructionSummary("WRECK", 0.71, 37)
                ));
        OwnShipPositionHolder positionHolder = new OwnShipPositionHolder();
        positionHolder.update(ownShip());
        EnvironmentContextService service = service(
                new RiskObjectMetaProperties(),
                new WeatherContextHolder(),
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                hydrologyContextService,
                positionHolder
        );

        Map<String, Object> environmentContext = service.refresh(
                EnvironmentUpdateReason.OWN_SHIP_ENV_REEVALUATED).snapshot().environmentContext();

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
    void refreshCombinesWeatherAndHydrologyAlerts() {
        WeatherContextHolder weatherContextHolder = new WeatherContextHolder();
        weatherContextHolder.update(new WeatherContext(
                "FOG",
                0.8,
                0.0,
                null,
                null,
                2,
                Instant.now()
        ), List.of());
        HydrologyContextService hydrologyContextService = mock(HydrologyContextService.class);
        when(hydrologyContextService.resolve(anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(new HydrologyContext(null, null, null));
        OwnShipPositionHolder positionHolder = new OwnShipPositionHolder();
        positionHolder.update(ownShip());
        EnvironmentContextService service = service(
                new RiskObjectMetaProperties(),
                weatherContextHolder,
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                hydrologyContextService,
                positionHolder
        );

        Map<String, Object> environmentContext = service.refresh(
                EnvironmentUpdateReason.OWN_SHIP_ENV_REEVALUATED).snapshot().environmentContext();

        assertThat(environmentContext.get("active_alerts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("LOW_VISIBILITY", "DEPTH_DATA_MISSING");
    }

    @Test
    void refreshIncludesActiveWeatherRiskAdjustmentState() {
        WeatherContextHolder weatherContextHolder = new WeatherContextHolder();
        weatherContextHolder.update(new WeatherContext(
                "FOG",
                0.8,
                0.0,
                null,
                null,
                2,
                Instant.now()
        ), List.of());
        WeatherRiskProperties weatherRiskProperties = new WeatherRiskProperties();
        weatherRiskProperties.getVisibility().setEnabled(true);
        EnvironmentContextService service = service(
                new RiskObjectMetaProperties(),
                weatherContextHolder,
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                mock(HydrologyContextService.class),
                new OwnShipPositionHolder(),
                weatherRiskProperties
        );

        Map<String, Object> environmentContext = service.refresh(
                EnvironmentUpdateReason.WEATHER_UPDATED).snapshot().environmentContext();

        assertThat(environmentContext.get("weather"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("risk_adjustment_active", true)
                .extractingByKey("risk_adjustment_reasons")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("VISIBILITY");
    }

    @Test
    void refreshDoesNotInferWeatherRiskAdjustmentFromAlertsWhenDisabled() {
        WeatherContextHolder weatherContextHolder = new WeatherContextHolder();
        weatherContextHolder.update(new WeatherContext(
                "FOG",
                0.8,
                0.0,
                null,
                null,
                2,
                Instant.now()
        ), List.of());
        EnvironmentContextService service = service(
                new RiskObjectMetaProperties(),
                weatherContextHolder,
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                mock(HydrologyContextService.class),
                new OwnShipPositionHolder()
        );

        Map<String, Object> environmentContext = service.refresh(
                EnvironmentUpdateReason.WEATHER_UPDATED).snapshot().environmentContext();

        assertThat(environmentContext.get("active_alerts"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("LOW_VISIBILITY");
        assertThat(environmentContext.get("weather"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("risk_adjustment_active", false)
                .extractingByKey("risk_adjustment_reasons")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .isEmpty();
    }

    @Test
    void refreshIncludesStormWeatherRiskAdjustmentReason() {
        WeatherContextHolder weatherContextHolder = new WeatherContextHolder();
        weatherContextHolder.update(new WeatherContext(
                "RAIN",
                4.0,
                0.0,
                null,
                null,
                7,
                Instant.now()
        ), List.of());
        WeatherRiskProperties weatherRiskProperties = new WeatherRiskProperties();
        weatherRiskProperties.getStorm().setEnabled(true);
        EnvironmentContextService service = service(
                new RiskObjectMetaProperties(),
                weatherContextHolder,
                new SafetyContourStateHolder(new RiskObjectMetaProperties()),
                mock(HydrologyContextService.class),
                new OwnShipPositionHolder(),
                weatherRiskProperties
        );

        Map<String, Object> environmentContext = service.refresh(
                EnvironmentUpdateReason.WEATHER_UPDATED).snapshot().environmentContext();

        assertThat(environmentContext.get("weather"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("risk_adjustment_active", true)
                .extractingByKey("risk_adjustment_reasons")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("STORM");
    }

    private EnvironmentContextService service(
            RiskObjectMetaProperties properties,
            WeatherContextHolder weatherContextHolder,
            SafetyContourStateHolder safetyContourStateHolder,
            HydrologyContextService hydrologyContextService,
            OwnShipPositionHolder positionHolder
    ) {
        return service(properties, weatherContextHolder, safetyContourStateHolder, hydrologyContextService, positionHolder, new WeatherRiskProperties());
    }

    private EnvironmentContextService service(
            RiskObjectMetaProperties properties,
            WeatherContextHolder weatherContextHolder,
            SafetyContourStateHolder safetyContourStateHolder,
            HydrologyContextService hydrologyContextService,
            OwnShipPositionHolder positionHolder,
            WeatherRiskProperties weatherRiskProperties
    ) {
        return new EnvironmentContextService(
                properties,
                weatherContextHolder,
                new WeatherAlertProperties(),
                new RegionalWeatherResolver(),
                hydrologyContextService,
                safetyContourStateHolder,
                positionHolder,
                new WeatherRiskAdjustmentEvaluator(weatherRiskProperties)
        );
    }

    private ShipStatus ownShip() {
        return ShipStatus.builder()
                .id("OWN")
                .role(ShipRole.OWN_SHIP)
                .latitude(0.0)
                .longitude(0.0)
                .build();
    }
}
