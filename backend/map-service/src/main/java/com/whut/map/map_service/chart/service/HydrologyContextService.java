package com.whut.map.map_service.chart.service;

import com.whut.map.map_service.chart.dto.GeoPoint;
import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.HydrologyRouteAssessment;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class HydrologyContextService {

    private static final double MAX_SHOAL_SEARCH_NM = 3.0;
    private static final double MAX_OBSTRUCTION_SEARCH_NM = 2.0;
    private static final double METERS_PER_NM = 1852.0;

    private final JdbcTemplate jdbcTemplate;

    public HydrologyContextService(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider.getIfAvailable();
    }

    public HydrologyContext resolve(double latitude, double longitude, double effectiveSafetyContourMeters) {
        return resolve(latitude, longitude, effectiveSafetyContourMeters, MAX_SHOAL_SEARCH_NM);
    }

    public HydrologyContext resolve(
            double latitude,
            double longitude,
            double effectiveSafetyContourMeters,
            double searchRadiusNm
    ) {
        if (jdbcTemplate == null) {
            return null;
        }
        try {
            double shoalRadiusNm = effectiveShoalSearchRadiusNm(searchRadiusNm);
            double obstructionRadiusNm = effectiveObstructionSearchRadiusNm(searchRadiusNm);
            Double ownShipMinDepthM = queryOwnShipMinDepth(latitude, longitude);
            Double nearestShoalNm = queryNearestShoalNm(latitude, longitude, effectiveSafetyContourMeters, shoalRadiusNm);
            NearestObstructionSummary nearestObstruction = queryNearestObstruction(latitude, longitude, obstructionRadiusNm);
            return new HydrologyContext(ownShipMinDepthM, nearestShoalNm, nearestObstruction);
        } catch (DataAccessException ex) {
            log.warn("Failed to resolve hydrology context: {}", ex.getMessage());
            return null;
        }
    }

    public HydrologyRouteAssessment evaluateRoute(
            List<GeoPoint> sampledPoints,
            double effectiveSafetyContourMeters,
            double searchRadiusNm
    ) {
        if (sampledPoints == null || sampledPoints.isEmpty()) {
            return new HydrologyRouteAssessment(null, false, null, null, 0, 0, false);
        }

        Double minDepthM = null;
        boolean crossesShoal = false;
        Double nearestShoalNm = null;
        NearestObstructionSummary nearestObstruction = null;
        int resolvedSampleCount = 0;

        for (GeoPoint point : sampledPoints) {
            if (point == null) {
                continue;
            }
            HydrologyContext context = resolve(
                    point.latitude(),
                    point.longitude(),
                    effectiveSafetyContourMeters,
                    searchRadiusNm
            );
            if (context == null) {
                continue;
            }
            resolvedSampleCount++;
            if (context.ownShipMinDepthM() != null) {
                minDepthM = minDepthM == null
                        ? context.ownShipMinDepthM()
                        : Math.min(minDepthM, context.ownShipMinDepthM());
                if (context.ownShipMinDepthM() < effectiveSafetyContourMeters) {
                    crossesShoal = true;
                }
            }
            if (context.nearestShoalNm() != null) {
                nearestShoalNm = nearestShoalNm == null
                        ? context.nearestShoalNm()
                        : Math.min(nearestShoalNm, context.nearestShoalNm());
                if (context.nearestShoalNm() <= 0.0) {
                    crossesShoal = true;
                }
            }
            NearestObstructionSummary obstruction = context.nearestObstruction();
            if (obstruction != null && obstruction.distanceNm() != null) {
                if (nearestObstruction == null
                        || nearestObstruction.distanceNm() == null
                        || obstruction.distanceNm() < nearestObstruction.distanceNm()) {
                    nearestObstruction = obstruction;
                }
            }
        }

        return new HydrologyRouteAssessment(
                minDepthM,
                crossesShoal,
                nearestShoalNm,
                nearestObstruction,
                sampledPoints.size(),
                resolvedSampleCount,
                resolvedSampleCount == sampledPoints.size()
        );
    }

    public double effectiveShoalSearchRadiusNm(double searchRadiusNm) {
        return Math.min(validSearchRadiusNm(searchRadiusNm), MAX_SHOAL_SEARCH_NM);
    }

    public double effectiveObstructionSearchRadiusNm(double searchRadiusNm) {
        return Math.min(validSearchRadiusNm(searchRadiusNm), MAX_OBSTRUCTION_SEARCH_NM);
    }

    private Double queryOwnShipMinDepth(double latitude, double longitude) {
        List<Double> matches = jdbcTemplate.queryForList("""
                SELECT "DRVAL1"
                FROM enc_depare
                WHERE ST_Intersects(
                    geometry,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)
                )
                ORDER BY "DRVAL1" ASC NULLS LAST
                LIMIT 1
                """, Double.class, longitude, latitude);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private Double queryNearestShoalNm(
            double latitude,
            double longitude,
            double effectiveSafetyContourMeters,
            double searchRadiusNm
    ) {
        List<Double> matches = jdbcTemplate.queryForList("""
                WITH ownship AS (
                    SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS geog
                )
                SELECT ST_Distance(ownship.geog, ST_ClosestPoint(d.geometry, ownship.geog::geometry)::geography) / ?
                FROM enc_depare d, ownship
                WHERE d."DRVAL1" < ?
                  AND ST_DWithin(d.geometry::geography, ownship.geog, ?)
                ORDER BY ST_Distance(ownship.geog, ST_ClosestPoint(d.geometry, ownship.geog::geometry)::geography) ASC
                LIMIT 1
                """,
                Double.class,
                longitude,
                latitude,
                METERS_PER_NM,
                effectiveSafetyContourMeters,
                searchRadiusNm * METERS_PER_NM);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private NearestObstructionSummary queryNearestObstruction(
            double latitude,
            double longitude,
            double searchRadiusNm
    ) {
        List<Map<String, Object>> matches = jdbcTemplate.queryForList("""
                WITH ownship AS (
                    SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS geog
                )
                SELECT
                    o."CATOBS"::text AS category,
                    ST_Distance(ownship.geog, o.geometry::geography) / ? AS distance_nm,
                    MOD(ROUND(DEGREES(ST_Azimuth(
                        ownship.geog::geometry,
                        ST_ClosestPoint(o.geometry, ownship.geog::geometry)
                    )))::int + 360, 360) AS bearing_deg
                FROM enc_obstrn o, ownship
                WHERE ST_DWithin(o.geometry::geography, ownship.geog, ?)
                ORDER BY ST_Distance(ownship.geog, o.geometry::geography) ASC
                LIMIT 1
                """,
                longitude,
                latitude,
                METERS_PER_NM,
                searchRadiusNm * METERS_PER_NM);
        if (matches.isEmpty()) {
            return null;
        }

        Map<String, Object> row = matches.getFirst();
        return new NearestObstructionSummary(
                row.get("category") == null ? null : row.get("category").toString(),
                toDouble(row.get("distance_nm")),
                toInteger(row.get("bearing_deg"))
        );
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }

    private double validSearchRadiusNm(double searchRadiusNm) {
        if (!Double.isFinite(searchRadiusNm) || searchRadiusNm <= 0.0) {
            return 0.0;
        }
        return searchRadiusNm;
    }
}
