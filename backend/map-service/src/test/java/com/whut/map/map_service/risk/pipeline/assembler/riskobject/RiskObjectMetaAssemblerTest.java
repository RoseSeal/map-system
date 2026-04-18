package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import org.junit.jupiter.api.Test;

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
                WEATHER_ALERT_PROPERTIES
        );

        Map<String, Object> governance = assembler.buildGovernance(0.42);

        assertThat(governance).containsEntry("mode", "manual");
        assertThat(governance).containsEntry("trust_factor", 0.42);
    }

    @Test
    void buildEnvironmentContextUsesConfiguredSafetyContour() {
        RiskObjectMetaProperties properties = new RiskObjectMetaProperties();
        properties.setSafetyContourVal(12.5);
        RiskObjectMetaAssembler assembler = new RiskObjectMetaAssembler(
                properties,
                new WeatherContextHolder(),
                WEATHER_ALERT_PROPERTIES
        );

        Map<String, Object> environmentContext = assembler.buildEnvironmentContext();

        assertThat(environmentContext).containsEntry("safety_contour_val", 12.5);
        assertThat(environmentContext).containsEntry("active_alerts", java.util.List.of());
        assertThat(environmentContext).containsEntry("weather", null);
    }
}