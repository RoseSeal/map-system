package com.whut.map.map_service.risk.environment;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.chart.service.HydrologyContextService;
import com.whut.map.map_service.chart.service.SafetyContourStateHolder;
import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.EnvAlertCode;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class EnvironmentContextService {

    private static final Set<String> VALID_GEOMETRY_TYPES = Set.of("Polygon", "MultiPolygon");

    private final RiskObjectMetaProperties properties;
    private final WeatherContextHolder weatherContextHolder;
    private final WeatherAlertProperties weatherAlertProperties;
    private final RegionalWeatherResolver regionalWeatherResolver;
    private final HydrologyContextService hydrologyContextService;
    private final SafetyContourStateHolder safetyContourStateHolder;
    private final OwnShipPositionHolder ownShipPositionHolder;
    private final AtomicLong environmentStateVersion = new AtomicLong(0L);

    private volatile EnvironmentStateSnapshot latestSnapshot;

    public EnvironmentContextService(
            RiskObjectMetaProperties properties,
            WeatherContextHolder weatherContextHolder,
            WeatherAlertProperties weatherAlertProperties,
            RegionalWeatherResolver regionalWeatherResolver,
            HydrologyContextService hydrologyContextService,
            SafetyContourStateHolder safetyContourStateHolder,
            OwnShipPositionHolder ownShipPositionHolder
    ) {
        this.properties = properties;
        this.weatherContextHolder = weatherContextHolder;
        this.weatherAlertProperties = weatherAlertProperties;
        this.regionalWeatherResolver = regionalWeatherResolver;
        this.hydrologyContextService = hydrologyContextService;
        this.safetyContourStateHolder = safetyContourStateHolder;
        this.ownShipPositionHolder = ownShipPositionHolder;
    }

    public EnvironmentRefreshResult refresh(EnvironmentUpdateReason reason) {
        Map<String, Object> environmentContext = buildEnvironmentContext();
        synchronized (this) {
            if (reason == EnvironmentUpdateReason.WEATHER_EXPIRED && hasFreshWeather()) {
                return new EnvironmentRefreshResult(latestSnapshot, reason, changedFieldsFor(reason), false);
            }

            EnvironmentStateSnapshot previous = latestSnapshot;
            boolean changed = previous == null || !Objects.equals(previous.environmentContext(), environmentContext);
            boolean publish = changed || shouldAlwaysPublish(reason);
            long version = changed ? environmentStateVersion.incrementAndGet() : environmentStateVersion.get();
            EnvironmentStateSnapshot snapshot = new EnvironmentStateSnapshot(
                    version,
                    Instant.now().toString(),
                    environmentContext
            );
            latestSnapshot = snapshot;
            return new EnvironmentRefreshResult(snapshot, reason, changedFieldsFor(reason), publish);
        }
    }

    public boolean latestEnvironmentContainsWeather() {
        EnvironmentStateSnapshot snapshot = latestSnapshot;
        return snapshot != null && snapshot.environmentContext().get("weather") != null;
    }

    public boolean hasFreshWeather() {
        return weatherContextHolder.getFreshSnapshot(staleThreshold()).isPresent();
    }

    private Map<String, Object> buildEnvironmentContext() {
        double effectiveSafetyContourMeters = safetyContourStateHolder.getCurrentDepthMeters();
        OwnShipPositionHolder.OwnShipPosition ownShipPosition = ownShipPositionHolder.getCurrentPosition();
        HydrologyContext hydrologyContext = ownShipPosition == null ? null : hydrologyContextService.resolve(
                ownShipPosition.latitude(),
                ownShipPosition.longitude(),
                effectiveSafetyContourMeters
        );

        Optional<WeatherContextHolder.Snapshot> snapshotOpt = weatherContextHolder.getFreshSnapshot(staleThreshold());
        WeatherContext globalContext = null;
        List<WeatherZoneContext> zones = List.of();
        Instant snapshotUpdatedAt = null;

        if (snapshotOpt.isPresent()) {
            WeatherContextHolder.Snapshot snap = snapshotOpt.get();
            globalContext = snap.globalContext();
            zones = snap.zones();
            snapshotUpdatedAt = snap.updatedAt();
        }

        WeatherContext effectiveWeather;
        String sourceZoneId;

        if (!zones.isEmpty() && ownShipPosition != null) {
            Optional<WeatherZoneContext> matchedZone = regionalWeatherResolver.resolve(
                    ownShipPosition.latitude(), ownShipPosition.longitude(), zones);
            if (matchedZone.isPresent()) {
                effectiveWeather = zoneToWeatherContext(matchedZone.get(), snapshotUpdatedAt);
                sourceZoneId = matchedZone.get().zoneId();
            } else {
                effectiveWeather = globalContext;
                sourceZoneId = null;
            }
        } else {
            effectiveWeather = globalContext;
            sourceZoneId = null;
        }

        List<EnvAlertCode> alerts = new ArrayList<>();
        if (effectiveWeather != null) {
            evaluateWeatherAlerts(effectiveWeather, alerts);
        }
        if (hydrologyContext != null) {
            evaluateHydrologyAlerts(hydrologyContext, alerts);
        }

        Map<String, Object> weatherPayload = null;
        if (effectiveWeather != null) {
            weatherPayload = toWeatherPayload(effectiveWeather);
            weatherPayload.put("source_zone_id", sourceZoneId);
        }

        List<Map<String, Object>> zonesPayload = zones.isEmpty() ? null : toZonesPayload(zones);

        Map<String, Object> environmentContext = new LinkedHashMap<>();
        environmentContext.put("safety_contour_val", effectiveSafetyContourMeters);
        environmentContext.put("active_alerts", alerts.stream().map(Enum::name).toList());
        environmentContext.put("weather", weatherPayload);
        environmentContext.put("weather_zones", weatherPayload == null ? null : zonesPayload);
        environmentContext.put("hydrology", toHydrologyPayload(hydrologyContext));
        return environmentContext;
    }

    private Duration staleThreshold() {
        return Duration.ofSeconds(weatherAlertProperties.getStaleThresholdSeconds());
    }

    private boolean shouldAlwaysPublish(EnvironmentUpdateReason reason) {
        return reason == EnvironmentUpdateReason.SAFETY_CONTOUR_UPDATED
                || reason == EnvironmentUpdateReason.SAFETY_CONTOUR_RESET
                || reason == EnvironmentUpdateReason.WEATHER_UPDATED;
    }

    private List<String> changedFieldsFor(EnvironmentUpdateReason reason) {
        return switch (reason) {
            case WEATHER_UPDATED, WEATHER_EXPIRED ->
                    List.of("weather", "weather_zones", "active_alerts");
            case SAFETY_CONTOUR_UPDATED, SAFETY_CONTOUR_RESET ->
                    List.of("safety_contour_val", "hydrology", "active_alerts");
            case OWN_SHIP_ENV_REEVALUATED ->
                    List.of("weather", "hydrology", "active_alerts");
        };
    }

    private WeatherContext zoneToWeatherContext(WeatherZoneContext zone, Instant snapshotUpdatedAt) {
        Instant updatedAt = zone.updatedAt() != null ? zone.updatedAt() : snapshotUpdatedAt;
        return new WeatherContext(
                zone.weatherCode(),
                zone.visibilityNm(),
                zone.precipitationMmPerHr(),
                zone.wind(),
                zone.surfaceCurrent(),
                zone.seaState(),
                updatedAt
        );
    }

    private void evaluateWeatherAlerts(WeatherContext weather, List<EnvAlertCode> alerts) {
        if (weather.visibilityNm() != null && weather.visibilityNm() < weatherAlertProperties.getLowVisibilityNm()) {
            alerts.add(EnvAlertCode.LOW_VISIBILITY);
        }

        WeatherContext.Wind wind = weather.wind();
        if (wind != null && wind.speedKn() != null && wind.speedKn() > weatherAlertProperties.getHighWindKn()) {
            alerts.add(EnvAlertCode.HIGH_WIND);
        }

        if (weather.precipitationMmPerHr() != null
                && weather.precipitationMmPerHr() > weatherAlertProperties.getHeavyPrecipitationMmPerHr()) {
            alerts.add(EnvAlertCode.HEAVY_PRECIPITATION);
        }

        WeatherContext.SurfaceCurrent surfaceCurrent = weather.surfaceCurrent();
        if (surfaceCurrent != null
                && surfaceCurrent.speedKn() != null
                && surfaceCurrent.speedKn() > weatherAlertProperties.getStrongCurrentSetKn()) {
            alerts.add(EnvAlertCode.STRONG_CURRENT_SET);
        }
    }

    private void evaluateHydrologyAlerts(HydrologyContext hydrology, List<EnvAlertCode> alerts) {
        if (hydrology.ownShipMinDepthM() == null) {
            alerts.add(EnvAlertCode.DEPTH_DATA_MISSING);
        }
        if (hydrology.nearestShoalNm() != null
                && hydrology.nearestShoalNm() <= properties.getShoalProximityAlertNm()) {
            alerts.add(EnvAlertCode.SHOAL_PROXIMITY);
        }
        if (hydrology.nearestObstruction() != null) {
            alerts.add(EnvAlertCode.OBSTRUCTION_NEARBY);
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

    private List<Map<String, Object>> toZonesPayload(List<WeatherZoneContext> zones) {
        return zones.stream()
                .filter(z -> z.geometry() != null && VALID_GEOMETRY_TYPES.contains(z.geometry().type()))
                .map(this::toZoneMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toHydrologyPayload(HydrologyContext hydrology) {
        if (hydrology == null) {
            return null;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("own_ship_min_depth_m", hydrology.ownShipMinDepthM());
        payload.put("nearest_shoal_nm", hydrology.nearestShoalNm());
        payload.put("nearest_obstruction", toNearestObstructionPayload(hydrology.nearestObstruction()));
        return payload;
    }

    private Map<String, Object> toNearestObstructionPayload(NearestObstructionSummary obstruction) {
        if (obstruction == null) {
            return null;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("category", obstruction.category());
        payload.put("distance_nm", obstruction.distanceNm());
        payload.put("bearing_deg", obstruction.bearingDeg());
        return payload;
    }

    private Map<String, Object> toZoneMap(WeatherZoneContext zone) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("zone_id", zone.zoneId());
        map.put("weather_code", zone.weatherCode());
        map.put("visibility_nm", zone.visibilityNm());
        map.put("precipitation_mm_per_hr", zone.precipitationMmPerHr());
        map.put("wind", toWindPayload(zone.wind()));
        map.put("surface_current", toSurfaceCurrentPayload(zone.surfaceCurrent()));
        map.put("sea_state", zone.seaState());
        map.put("updated_at", formatUpdatedAt(zone.updatedAt()));
        Map<String, Object> geomMap = new LinkedHashMap<>();
        geomMap.put("type", zone.geometry().type());
        geomMap.put("coordinates", zone.geometry().coordinates());
        map.put("geometry", geomMap);
        return map;
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
