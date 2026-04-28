package com.whut.map.map_service.chart.service;

import com.whut.map.map_service.chart.dto.HydrologyContext;
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
        if (jdbcTemplate == null) {
            return null;
        }
        try {
            Double ownShipMinDepthM = queryOwnShipMinDepth(latitude, longitude);
            Double nearestShoalNm = queryNearestShoalNm(latitude, longitude, effectiveSafetyContourMeters);
            NearestObstructionSummary nearestObstruction = queryNearestObstruction(latitude, longitude);
            return new HydrologyContext(ownShipMinDepthM, nearestShoalNm, nearestObstruction);
        } catch (DataAccessException ex) {
            log.warn("Failed to resolve hydrology context: {}", ex.getMessage());
            return null;
        }
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

    private Double queryNearestShoalNm(double latitude, double longitude, double effectiveSafetyContourMeters) {
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
                MAX_SHOAL_SEARCH_NM * METERS_PER_NM);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private NearestObstructionSummary queryNearestObstruction(double latitude, double longitude) {
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
                MAX_OBSTRUCTION_SEARCH_NM * METERS_PER_NM);
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
}
