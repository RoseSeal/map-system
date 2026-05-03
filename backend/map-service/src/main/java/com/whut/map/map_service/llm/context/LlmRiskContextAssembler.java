package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassifier;
import com.whut.map.map_service.risk.engine.encounter.EncounterRoleResolver;
import com.whut.map.map_service.risk.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.dto.LlmRiskWeatherContext;
import com.whut.map.map_service.shared.context.WeatherContextHolder;
import com.whut.map.map_service.shared.domain.EnvAlertCode;
import com.whut.map.map_service.shared.util.GeoUtils;
import com.whut.map.map_service.source.weather.RegionalWeatherResolver;
import com.whut.map.map_service.source.weather.config.WeatherAlertProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

@Component
public class LlmRiskContextAssembler {

    private final EncounterClassifier encounterClassifier;
    private final EncounterRoleResolver encounterRoleResolver;
    private final WeatherContextHolder weatherContextHolder;
    private final RegionalWeatherResolver regionalWeatherResolver;
    private final WeatherAlertProperties weatherAlertProperties;

    @Autowired
    public LlmRiskContextAssembler(
            EncounterClassifier encounterClassifier,
            EncounterRoleResolver encounterRoleResolver,
            WeatherContextHolder weatherContextHolder,
            RegionalWeatherResolver regionalWeatherResolver,
            WeatherAlertProperties weatherAlertProperties
    ) {
        this.encounterClassifier = encounterClassifier;
        this.encounterRoleResolver = encounterRoleResolver;
        this.weatherContextHolder = weatherContextHolder;
        this.regionalWeatherResolver = regionalWeatherResolver;
        this.weatherAlertProperties = weatherAlertProperties;
    }

    public LlmRiskContextAssembler(EncounterClassifier encounterClassifier) {
        this(encounterClassifier, new EncounterRoleResolver(), null, null, null);
    }

    public LlmRiskContext assemble(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        if (ownShip == null) {
            return null;
        }

        Map<String, Double> currentDistancesNm = buildCurrentDistancesNm(ownShip, allShips);

        return LlmRiskContext.builder()
                .ownShip(buildOwnShipContext(ownShip))
                .targets(buildTargetContexts(ownShip, allShips, currentDistancesNm, cpaResults, riskResult))
                .weather(resolveWeatherContext(ownShip))
                .build();
    }

    private LlmRiskWeatherContext resolveWeatherContext(ShipStatus ownShip) {
        if (weatherContextHolder == null || weatherAlertProperties == null) {
            return null;
        }

        Duration staleThreshold = Duration.ofSeconds(weatherAlertProperties.getStaleThresholdSeconds());
        Optional<WeatherContextHolder.Snapshot> snapshotOpt = weatherContextHolder.getFreshSnapshot(staleThreshold);
        if (snapshotOpt.isEmpty()) {
            return null;
        }

        WeatherContextHolder.Snapshot snap = snapshotOpt.get();
        WeatherContext effectiveWeather;
        String sourceZoneId;

        List<WeatherZoneContext> zones = snap.zones();
        if (!zones.isEmpty() && ownShip != null && regionalWeatherResolver != null) {
            Optional<WeatherZoneContext> matchedZone = regionalWeatherResolver.resolve(
                    ownShip.getLatitude(), ownShip.getLongitude(), zones);
            if (matchedZone.isPresent()) {
                WeatherZoneContext zone = matchedZone.get();
                effectiveWeather = new WeatherContext(
                        zone.weatherCode(),
                        zone.visibilityNm(),
                        zone.precipitationMmPerHr(),
                        zone.wind(),
                        zone.surfaceCurrent(),
                        zone.seaState(),
                        zone.updatedAt() != null ? zone.updatedAt() : snap.updatedAt()
                );
                sourceZoneId = zone.zoneId();
            } else {
                effectiveWeather = snap.globalContext();
                sourceZoneId = null;
            }
        } else {
            effectiveWeather = snap.globalContext();
            sourceZoneId = null;
        }

        if (effectiveWeather == null) {
            return null;
        }

        List<String> alerts = buildActiveAlerts(effectiveWeather);

        Double windSpeedKn = null;
        Integer windDirectionFromDeg = null;
        if (effectiveWeather.wind() != null) {
            windSpeedKn = effectiveWeather.wind().speedKn();
            if (effectiveWeather.wind().directionFromDeg() != null) {
                windDirectionFromDeg = effectiveWeather.wind().directionFromDeg().intValue();
            }
        }

        Double surfaceCurrentSpeedKn = null;
        Integer surfaceCurrentSetDeg = null;
        if (effectiveWeather.surfaceCurrent() != null) {
            surfaceCurrentSpeedKn = effectiveWeather.surfaceCurrent().speedKn();
            if (effectiveWeather.surfaceCurrent().setDeg() != null) {
                surfaceCurrentSetDeg = effectiveWeather.surfaceCurrent().setDeg().intValue();
            }
        }

        return LlmRiskWeatherContext.builder()
                .weatherCode(effectiveWeather.weatherCode())
                .visibilityNm(effectiveWeather.visibilityNm())
                .windSpeedKn(windSpeedKn)
                .windDirectionFromDeg(windDirectionFromDeg)
                .surfaceCurrentSpeedKn(surfaceCurrentSpeedKn)
                .surfaceCurrentSetDeg(surfaceCurrentSetDeg)
                .seaState(effectiveWeather.seaState())
                .sourceZoneId(sourceZoneId)
                .activeAlerts(alerts)
                .build();
    }

    private List<String> buildActiveAlerts(WeatherContext weather) {
        List<String> alerts = new ArrayList<>();
        if (weather.visibilityNm() != null && weather.visibilityNm() < weatherAlertProperties.getLowVisibilityNm()) {
            alerts.add(EnvAlertCode.LOW_VISIBILITY.name());
        }
        WeatherContext.Wind wind = weather.wind();
        if (wind != null && wind.speedKn() != null && wind.speedKn() > weatherAlertProperties.getHighWindKn()) {
            alerts.add(EnvAlertCode.HIGH_WIND.name());
        }
        if (weather.precipitationMmPerHr() != null
                && weather.precipitationMmPerHr() > weatherAlertProperties.getHeavyPrecipitationMmPerHr()) {
            alerts.add(EnvAlertCode.HEAVY_PRECIPITATION.name());
        }
        WeatherContext.SurfaceCurrent surfaceCurrent = weather.surfaceCurrent();
        if (surfaceCurrent != null && surfaceCurrent.speedKn() != null
                && surfaceCurrent.speedKn() > weatherAlertProperties.getStrongCurrentSetKn()) {
            alerts.add(EnvAlertCode.STRONG_CURRENT_SET.name());
        }
        return alerts;
    }

    private LlmRiskOwnShipContext buildOwnShipContext(ShipStatus ownShip) {
        return LlmRiskOwnShipContext.builder()
                .id(ownShip.getId())
                .longitude(ownShip.getLongitude())
                .latitude(ownShip.getLatitude())
                .sog(ownShip.getSog())
                .cog(ownShip.getCog())
                .heading(ownShip.getHeading())
                .confidence(ownShip.getConfidence())
                .build();
    }

    private List<LlmRiskTargetContext> buildTargetContexts(
            ShipStatus ownShip,
            Collection<ShipStatus> allShips,
            Map<String, Double> currentDistancesNm,
            Map<String, CpaTcpaResult> cpaResults,
            RiskAssessmentResult riskResult
    ) {
        List<LlmRiskTargetContext> targets = new ArrayList<>();
        if (allShips == null) {
            return targets;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }

            TargetRiskAssessment assessment = riskResult == null ? null : riskResult.getTargetAssessment(ship.getId());
            CpaTcpaResult cpaResult = cpaResults == null ? null : cpaResults.get(ship.getId());
            EncounterClassificationResult enc = encounterClassifier.classify(ownShip, ship);
            enc.setOwnShipRole(encounterRoleResolver.resolve(enc));

            targets.add(LlmRiskTargetContext.builder()
                    .targetId(ship.getId())
                    .riskLevel(resolveRiskLevel(assessment))
                    .currentDistanceNm(resolveCurrentDistanceNm(ship.getId(), currentDistancesNm))
                    .relativeBearingDeg(resolveRelativeBearingDeg(ownShip, ship))
                    .dcpaNm(GeoUtils.metersToNm(assessment == null ? 0.0 : assessment.getCpaDistanceMeters()))
                    .tcpaSec(assessment == null ? 0.0 : assessment.getTcpaSeconds())
                    .approaching(assessment != null && assessment.isApproaching())
                    .longitude(ship.getLongitude())
                    .latitude(ship.getLatitude())
                    .speedKn(ship.getSog())
                    .courseDeg(ship.getCog())
                    .confidence(ship.getConfidence())
                    .riskScore(assessment == null ? null : assessment.getRiskScore())
                    .domainPenetration(assessment == null ? null : assessment.getDomainPenetration())
                    .ruleExplanation(assessment == null ? null : assessment.getExplanationText())
                    .encounterType(enc.getEncounterType())
                    .build());
        }

        return targets;
    }

    private RiskLevel resolveRiskLevel(TargetRiskAssessment assessment) {
        return assessment == null ? null : RiskLevel.fromValue(assessment.getRiskLevel());
    }

    private Double resolveRelativeBearingDeg(ShipStatus ownShip, ShipStatus targetShip) {
        if (ownShip == null || targetShip == null) {
            return null;
        }
        double trueBearing = GeoUtils.trueBearing(
                ownShip.getLatitude(),
                ownShip.getLongitude(),
                targetShip.getLatitude(),
                targetShip.getLongitude()
        );
        double referenceHeading = (ownShip.getHeading() != null && ownShip.getHeading() < 360.0)
                ? ownShip.getHeading()
                : ownShip.getCog();
        return (trueBearing - referenceHeading + 360.0) % 360.0;
    }

    private Double resolveCurrentDistanceNm(String targetId, Map<String, Double> currentDistancesNm) {
        if (targetId == null || currentDistancesNm == null) {
            return null;
        }
        return currentDistancesNm.get(targetId);
    }

    private Map<String, Double> buildCurrentDistancesNm(ShipStatus ownShip, Collection<ShipStatus> allShips) {
        Map<String, Double> currentDistancesNm = new HashMap<>();
        if (ownShip == null || allShips == null) {
            return currentDistancesNm;
        }

        for (ShipStatus ship : allShips) {
            if (ship == null || ship.getId() == null || ship.getId().equals(ownShip.getId())) {
                continue;
            }

            double distanceMeters = GeoUtils.distanceMetersByXY(
                    ownShip.getLatitude(),
                    ownShip.getLongitude(),
                    ship.getLatitude(),
                    ship.getLongitude()
            );
            currentDistancesNm.put(ship.getId(), GeoUtils.metersToNm(distanceMeters));
        }

        return currentDistancesNm;
    }
}
