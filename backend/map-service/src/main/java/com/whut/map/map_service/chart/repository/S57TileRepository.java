package com.whut.map.map_service.chart.repository;

import com.whut.map.map_service.shared.util.TileUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Envelope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Set;

/**
 * Repository for generating S-57 ENC Chart MVT tiles
 * Supports standard S-57 layers: DEPARE, SOUNDG, COALNE, LNDARE, DEPCNT
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class S57TileRepository {

    private final JdbcTemplate jdbcTemplate;

    // S-57 standard layer mapping: layer name -> database table
    private static final Set<String> ALLOWED_LAYERS = Set.of(
            "LNDARE",   // Land Area
            "DEPARE",   // Depth Area
            "DEPCNT",   // Depth Contour (derived from DEPARE)
            "COALNE",   // Coastline
            "SOUNDG",   // Soundings (depth points)
            "OBSTRN"    // Obstructions (if available)
    );

    // Table name mapping
    private static String getTableName(String layerName) {
        return switch (layerName.toUpperCase()) {
            case "LNDARE" -> "enc_lndare";
            case "DEPARE" -> "enc_depare";
            case "DEPCNT" -> "enc_depare";  // DEPCNT is derived from DEPARE boundaries
            case "COALNE" -> "enc_coalne";
            case "SOUNDG" -> "enc_soundg";
            case "OBSTRN" -> "enc_obstrn";
            default -> null;
        };
    }

    /**
     * Generate MVT tile for S-57 layer
     * @param layerName S-57 layer name (LNDARE, DEPARE, COALNE, SOUNDG, DEPCNT)
     * @param z Zoom level
     * @param x Tile X coordinate
     * @param y Tile Y coordinate
     * @return MVT binary data
     */
    public byte[] getTile(String layerName, int z, int x, int y) {
        return getTile(layerName, z, x, y, null);
    }

    /**
     * Generate MVT tile with optional safety contour filtering
     * @param layerName S-57 layer name
     * @param z Zoom level
     * @param x Tile X coordinate
     * @param y Tile Y coordinate
     * @param safetyContour Optional safety contour depth value (for DEPARE/DEPCNT)
     * @return MVT binary data
     */
    public byte[] getTile(String layerName, int z, int x, int y, Double safetyContour) {
        // Security check
        if (!ALLOWED_LAYERS.contains(layerName.toUpperCase())) {
            log.warn("Illegal layer request: {}", layerName);
            return new byte[0];
        }

        String tableName = getTableName(layerName);
        if (tableName == null) {
            log.warn("No table mapping for layer: {}", layerName);
            return new byte[0];
        }

        // Calculate tile bounds in Web Mercator (EPSG:3857)
        Envelope env = TileUtils.tileToEnvelope(x, y, z);

        // Build SQL based on layer type
        String sql = buildTileSQL(layerName, tableName, safetyContour);

        try {
            // Execute query and return MVT binary
            byte[] result = jdbcTemplate.queryForObject(
                    sql,
                    byte[].class,
                    env.getMinX(), env.getMinY(), env.getMaxX(), env.getMaxY()
            );
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            log.error("Failed to generate tile: layer={}, z={}, x={}, y={}, error={}",
                    layerName, z, x, y, e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Build MVT SQL based on layer type and properties
     */
    private String buildTileSQL(String layerName, String tableName, Double safetyContour) {
        String upperLayerName = layerName.toUpperCase();

        // Special handling for DEPCNT (depth contours - extract from DEPARE boundaries)
        if ("DEPCNT".equals(upperLayerName)) {
            return buildDepthContourSQL(safetyContour);
        }

        // Standard MVT generation SQL
        StringBuilder sql = new StringBuilder("""
            WITH bounds AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 3857) AS geom_env
            ),
            mvtgeom AS (
                SELECT ST_AsMVTGeom(
                    ST_Transform(t.geometry, 3857),
                    bounds.geom_env,
                    4096,
                    256,
                    true
                ) AS geom
            """);

        // Add attributes based on layer type
        sql.append(getLayerAttributes(upperLayerName));

        sql.append(String.format("""
                FROM %s t, bounds
                WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom_env)
            """, tableName));

        // Add layer-specific filters
        if ("DEPARE".equals(upperLayerName) && safetyContour != null) {
            sql.append(String.format(" AND (\"DRVAL1\" <= %f OR \"DRVAL2\" <= %f)", safetyContour, safetyContour));
        }

        sql.append(String.format("""
            )
            SELECT ST_AsMVT(mvtgeom.*, '%s', 4096, 'geom') FROM mvtgeom
            """, layerName));

        return sql.toString();
    }

    /**
     * Get layer-specific attributes for MVT
     */
    private String getLayerAttributes(String layerName) {
        // GeoPandas to_postgis converts column names to lowercase
        return switch (layerName) {
            case "DEPARE" -> """
                , t."DRVAL1" as drval1
                , t."DRVAL2" as drval2
                """;
            case "SOUNDG" -> """
                , t."DEPTH" as depth
                """;
            case "COALNE" -> """
                , t."CATCOA" as catcoa
                """;
            case "LNDARE" -> """
                , t."CATLND" as catlnd
                """;
            case "OBSTRN" -> """
                , t."CATOBS" as catobs
                , t."VALSOU" as valsou
                """;
            default -> "";
        };
    }

    /**
     * Build SQL for depth contour lines (DEPCNT)
     * Extracts boundary lines from DEPARE polygons
     */
    private String buildDepthContourSQL(Double safetyContour) {
        String filterClause = safetyContour != null
                ? String.format(" AND (\"DRVAL1\" = %f OR \"DRVAL2\" = %f)", safetyContour, safetyContour)
                : "";

        return String.format("""
            WITH bounds AS (
                SELECT ST_MakeEnvelope(?, ?, ?, ?, 3857) AS geom_env
            ),
            contours AS (
                SELECT
                    ST_Boundary(geometry) as geometry,
                    "DRVAL1" as depth_value,
                    "DRVAL2" as depth_max
                FROM enc_depare
                WHERE ST_Intersects(ST_Transform(geometry, 3857),
                    (SELECT geom_env FROM bounds))
                %s
            ),
            mvtgeom AS (
                SELECT ST_AsMVTGeom(
                    ST_Transform(c.geometry, 3857),
                    bounds.geom_env,
                    4096,
                    256,
                    true
                ) AS geom,
                c.depth_value as valdco,
                c.depth_max
                FROM contours c, bounds
            )
            SELECT ST_AsMVT(mvtgeom.*, 'DEPCNT', 4096, 'geom') FROM mvtgeom
            """, filterClause);
    }

    /**
     * Check if a layer is supported
     */
    public boolean isLayerSupported(String layerName) {
        return ALLOWED_LAYERS.contains(layerName.toUpperCase());
    }

    /**
     * Get all supported layer names
     */
    public Set<String> getSupportedLayers() {
        return ALLOWED_LAYERS;
    }

    /**
     * Generate composite MVT tile containing all S-57 layers in a single tile
     * Generates each layer separately and returns them in order of priority
     * Frontend will receive multiple layers in the tile
     */
    public byte[] getCompositeTile(int z, int x, int y, Double safetyContour) {
        Envelope env = TileUtils.tileToEnvelope(x, y, z);
        boolean includeObstructionLayer = isTableAvailable("enc_obstrn");

        // Generate each layer separately and combine using proper MVT format
        // Each layer is a separate ST_AsMVT call with different layer name
        String sql = buildMultiLayerSQL(safetyContour, env, includeObstructionLayer);

        try {
            byte[] result = jdbcTemplate.queryForObject(sql, byte[].class);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            log.error("Failed to generate composite tile: z={}, x={}, y={}, error={}",
                    z, x, y, e.getMessage());
            return new byte[0];
        }
    }

    /**
     * Build SQL for multi-layer MVT tile
     * Uses UNION ALL to combine all features, then generates single MVT with layer names
     */
    private String buildMultiLayerSQL(Double safetyContour, Envelope env, boolean includeObstructionLayer) {
        double minX = env.getMinX();
        double minY = env.getMinY();
        double maxX = env.getMaxX();
        double maxY = env.getMaxY();

        String boundsExpr = String.format(Locale.US,
                "ST_MakeEnvelope(%f, %f, %f, %f, 3857)", minX, minY, maxX, maxY);
        String obstructionCte = includeObstructionLayer
                ? """
            ,
            obstrn_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'OBSTRN', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."CATOBS" as catobs, t."VALSOU" as valsou
                    FROM enc_obstrn t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            )
            """
                : "";
        String tileConcat = includeObstructionLayer
                ? "depare_mvt.tile || lndare_mvt.tile || coalne_mvt.tile || depcnt_mvt.tile || soundg_mvt.tile || obstrn_mvt.tile"
                : "depare_mvt.tile || lndare_mvt.tile || coalne_mvt.tile || depcnt_mvt.tile || soundg_mvt.tile";
        String tileSources = includeObstructionLayer
                ? "depare_mvt, lndare_mvt, coalne_mvt, depcnt_mvt, soundg_mvt, obstrn_mvt"
                : "depare_mvt, lndare_mvt, coalne_mvt, depcnt_mvt, soundg_mvt";

        // Generate proper multi-layer MVT using separate ST_AsMVT calls combined with ||
        // The key is that each layer MUST have data, otherwise the concat fails
        // Use a WITH clause to pre-compute bounds and each layer's MVT
        return String.format(Locale.US, """
            WITH bounds AS (
                SELECT %1$s AS geom
            ),
            depare_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'DEPARE', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."DRVAL1" as drval1, t."DRVAL2" as drval2
                    FROM enc_depare t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            ),
            lndare_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'LNDARE', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."CATLND" as catlnd
                    FROM enc_lndare t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            ),
            coalne_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'COALNE', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."CATCOA" as catcoa
                    FROM enc_coalne t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            ),
            depcnt_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'DEPCNT', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(ST_Boundary(t.geometry), 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."DRVAL1" as valdco
                    FROM enc_depare t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            ),
            soundg_mvt AS (
                SELECT COALESCE(ST_AsMVT(q.*, 'SOUNDG', 4096, 'geom'), ''::bytea) AS tile FROM (
                    SELECT ST_AsMVTGeom(ST_Transform(t.geometry, 3857), bounds.geom, 4096, 256, true) AS geom,
                        t."DEPTH" as depth
                    FROM enc_soundg t, bounds
                    WHERE ST_Intersects(ST_Transform(t.geometry, 3857), bounds.geom)
                ) q
            )
            %2$s
            SELECT %3$s
            FROM %4$s
            """, boundsExpr, obstructionCte, tileConcat, tileSources);
    }

    private boolean isTableAvailable(String tableName) {
        try {
            Boolean exists = jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL",
                    Boolean.class,
                    tableName
            );
            return Boolean.TRUE.equals(exists);
        } catch (Exception e) {
            log.warn("Failed to inspect table availability: table={}, error={}", tableName, e.getMessage());
            return false;
        }
    }
}
