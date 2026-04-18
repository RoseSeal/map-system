package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RiskObjectMetaAssembler {

    private static final String ALERT_LOW_VISIBILITY = "LOW_VISIBILITY";
    private static final String ALERT_HIGH_WIND = "HIGH_WIND";
    private static final String ALERT_HEAVY_PRECIPITATION = "HEAVY_PRECIPITATION";
    private static final String ALERT_STRONG_CURRENT_SET = "STRONG_CURRENT_SET";

    private final RiskObjectMetaProperties properties;
    private final WeatherContextHolder weatherContextHolder;
    private final WeatherAlertProperties weatherAlertProperties;

    public RiskObjectMetaAssembler(
            RiskObjectMetaProperties properties,
            WeatherContextHolder weatherContextHolder,
            WeatherAlertProperties weatherAlertProperties
    ) {
        this.properties = properties;
        this.weatherContextHolder = weatherContextHolder;
        this.weatherAlertProperties = weatherAlertProperties;
    }

    public String buildSnapshotTimestamp(Collection<ShipStatus> allShips, ShipStatus fallback) {
        OffsetDateTime latest = fallback == null ? null : fallback.getMsgTime();
        if (allShips != null) {
            for (ShipStatus ship : allShips) {
                if (ship == null || ship.getMsgTime() == null) {
                    continue;
                }
                if (latest == null || ship.getMsgTime().isAfter(latest)) {
                    latest = ship.getMsgTime();
                }
            }
        }
        return latest == null ? OffsetDateTime.now().toInstant().toString() : latest.toInstant().toString();
    }

    public String buildRiskObjectId(ShipStatus ownShip, String snapshotTimestamp) {
        return ownShip.getId() + "-" + snapshotTimestamp;
    }

    public Map<String, Object> buildGovernance(double trustFactor) {
        return Map.of("mode", properties.getGovernanceMode(), "trust_factor", trustFactor);
    }

    public Map<String, Object> buildEnvironmentContext() {
        Map<String, Object> environmentContext = new LinkedHashMap<>();
        environmentContext.put("safety_contour_val", properties.getSafetyContourVal());

        List<String> alerts = new ArrayList<>();
        WeatherContext weather = weatherContextHolder
                .getFreshContext(Duration.ofSeconds(weatherAlertProperties.getStaleThresholdSeconds()))
                .orElse(null);

        if (weather != null) {
            evaluateWeatherAlerts(weather, alerts);
        }

        environmentContext.put("active_alerts", List.copyOf(alerts));
        environmentContext.put("weather", weather == null ? null : toWeatherPayload(weather));
        return environmentContext;
    }

    private void evaluateWeatherAlerts(WeatherContext weather, List<String> alerts) {
        if (weather.visibilityNm() != null && weather.visibilityNm() < weatherAlertProperties.getLowVisibilityNm()) {
            alerts.add(ALERT_LOW_VISIBILITY);
        }

        WeatherContext.Wind wind = weather.wind();
        if (wind != null && wind.speedKn() != null && wind.speedKn() > weatherAlertProperties.getHighWindKn()) {
            alerts.add(ALERT_HIGH_WIND);
        }

        if (weather.precipitationMmPerHr() != null
                && weather.precipitationMmPerHr() > weatherAlertProperties.getHeavyPrecipitationMmPerHr()) {
            alerts.add(ALERT_HEAVY_PRECIPITATION);
        }

        WeatherContext.SurfaceCurrent surfaceCurrent = weather.surfaceCurrent();
        if (surfaceCurrent != null
                && surfaceCurrent.speedKn() != null
                && surfaceCurrent.speedKn() > weatherAlertProperties.getStrongCurrentSetKn()) {
            alerts.add(ALERT_STRONG_CURRENT_SET);
        }
    }

    private Map<String, Object> toWeatherPayload(WeatherContext weather) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weather_code", weather.weatherCode());
        payload.put("visibility_nm", weather.visibilityNm());
        payload.put("precipitation_mm_per_hr", weather.precipitationMmPerHr());
        payload.put("wind", toWindPayload(weather.wind()));
        payload.put("surface_current", toSurfaceCurrentPayload(weather.surfaceCurrent()));
        payload.put("sea_state", weather.seaState());
        payload.put("updated_at", formatUpdatedAt(weather.updatedAt()));
        return payload;
    }

    private Map<String, Object> toWindPayload(WeatherContext.Wind wind) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("speed_kn", wind == null ? null : wind.speedKn());
        payload.put("direction_from_deg", wind == null ? null : wind.directionFromDeg());
        return payload;
    }

    private Map<String, Object> toSurfaceCurrentPayload(WeatherContext.SurfaceCurrent surfaceCurrent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("speed_kn", surfaceCurrent == null ? null : surfaceCurrent.speedKn());
        payload.put("set_deg", surfaceCurrent == null ? null : surfaceCurrent.setDeg());
        return payload;
    }

    private String formatUpdatedAt(Instant updatedAt) {
        return updatedAt == null ? null : updatedAt.toString();
    }
}
