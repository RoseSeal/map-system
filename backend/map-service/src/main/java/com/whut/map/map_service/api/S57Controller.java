package com.whut.map.map_service.api;

import com.whut.map.map_service.service.s57.S57TileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * S-57 ENC Chart REST API Controller
 * Provides MVT tiles, layer metadata, safety contour, and style configuration
 */
@RestController
@RequestMapping("/api/s57")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")  // Enable CORS for frontend
public class S57Controller {

    private final S57TileService s57TileService;

    /**
     * 1. Vector Tile Endpoint - Standard MVT service
     * GET /api/s57/tiles/{z}/{x}/{y}.pbf
     * GET /api/s57/tiles/{z}/{x}/{y}.pbf?safety_contour=10.0
     */
    @GetMapping("/tiles/{z}/{x}/{y}.pbf")
    public ResponseEntity<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(required = false) Double safety_contour) {

        log.debug("Tile request: z={}, x={}, y={}, safety_contour={}", z, x, y, safety_contour);

        // Generate composite tile with all S-57 layers
        byte[] tile = generateCompositeTile(z, x, y, safety_contour);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"));
        headers.setCacheControl("public, max-age=3600");  // Cache for 1 hour

        return new ResponseEntity<>(tile, headers, HttpStatus.OK);
    }

    /**
     * Debug endpoint for single layer testing
     * GET /api/s57/tiles/{z}/{x}/{y}/{layer}.pbf
     */
    @GetMapping("/tiles/{z}/{x}/{y}/{layer}.pbf")
    public ResponseEntity<byte[]> getSingleLayerTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @PathVariable String layer) {

        log.info("Single layer tile request: layer={}, z={}, x={}, y={}", layer, z, x, y);

        byte[] tile = s57TileService.getTile(layer.toUpperCase(), z, x, y, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.mapbox-vector-tile"));
        return new ResponseEntity<>(tile, headers, HttpStatus.OK);
    }

    /**
     * Generate composite tile containing all S-57 layers
     * Uses database-level MVT concatenation for proper tile merging
     */
    private byte[] generateCompositeTile(int z, int x, int y, Double safetyContour) {
        // Use the new composite tile method from service
        byte[] compositeTile = s57TileService.getCompositeTile(z, x, y, safetyContour);
        if (compositeTile != null && compositeTile.length > 0) {
            return compositeTile;
        }

        // Fallback: try individual layers if composite fails
        String[] layers = {"DEPARE", "LNDARE", "COALNE", "DEPCNT", "SOUNDG"};
        for (String layer : layers) {
            byte[] layerTile = s57TileService.getTile(layer, z, x, y, safetyContour);
            if (layerTile != null && layerTile.length > 0) {
                return layerTile;
            }
        }

        return new byte[0];
    }

    /**
     * 2. Layer Metadata Endpoint
     * GET /api/s57/layers
     * Returns S-57 layer definitions with zoom levels and descriptions
     */
    @GetMapping("/layers")
    public ResponseEntity<Map<String, Object>> getLayerMetadata() {
        List<Map<String, Object>> layers = new ArrayList<>();

        // LNDARE - Land Area
        layers.add(Map.of(
                "id", "LNDARE",
                "type", "fill",
                "minzoom", 0,
                "maxzoom", 22,
                "description", "Land Area",
                "geometryType", "polygon"
        ));

        // DEPARE - Depth Area
        layers.add(Map.of(
                "id", "DEPARE",
                "type", "fill",
                "minzoom", 0,
                "maxzoom", 22,
                "description", "Depth Area",
                "geometryType", "polygon",
                "attributes", Map.of(
                        "DRVAL1", "Minimum depth (meters)",
                        "DRVAL2", "Maximum depth (meters)",
                        "VALDCO", "Depth type (known/estimated)"
                )
        ));

        // DEPCNT - Depth Contour
        layers.add(Map.of(
                "id", "DEPCNT",
                "type", "line",
                "minzoom", 8,
                "maxzoom", 22,
                "description", "Depth Contour",
                "geometryType", "line",
                "attributes", Map.of(
                        "VALDCO", "Contour depth value (meters)"
                )
        ));

        // COALNE - Coastline
        layers.add(Map.of(
                "id", "COALNE",
                "type", "line",
                "minzoom", 0,
                "maxzoom", 22,
                "description", "Coastline",
                "geometryType", "line"
        ));

        // SOUNDG - Soundings
        layers.add(Map.of(
                "id", "SOUNDG",
                "type", "symbol",
                "minzoom", 10,
                "maxzoom", 22,
                "description", "Depth Soundings",
                "geometryType", "point",
                "attributes", Map.of(
                        "DEPTH", "Depth value (meters)",
                        "QUASOU", "Quality of sounding"
                )
        ));

        return ResponseEntity.ok(Map.of(
                "version", "1.0",
                "layers", layers,
                "crs", "EPSG:3857",
                "bounds", Map.of(
                        "minLon", -73.8,
                        "minLat", 40.575,
                        "maxLon", -73.725,
                        "maxLat", 40.65
                )
        ));
    }

    /**
     * 3. Safety Contour Endpoint
     * GET /api/s57/safety-contour?depth=10.0
     * Returns safety contour configuration
     */
    @GetMapping("/safety-contour")
    public ResponseEntity<Map<String, Object>> getSafetyContour(
            @RequestParam(defaultValue = "10.0") double depth) {

        return ResponseEntity.ok(Map.of(
                "safetyContourDepth", depth,
                "unit", "meters",
                "description", "Areas shallower than this depth are highlighted as navigation hazards",
                "tileUrl", String.format("/api/s57/tiles/{z}/{x}/{y}.pbf?safety_contour=%.1f", depth)
        ));
    }

    /**
     * 4. Style Configuration Endpoint
     * GET /api/s57/style.json
     * Returns MapLibre Style JSON for direct frontend loading
     */
    @GetMapping(value = "/style.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getStyleJson() {
        Map<String, Object> style = new LinkedHashMap<>();

        style.put("version", 8);
        style.put("name", "S-57 ENC Chart");

        // Sources
        Map<String, Object> sources = new HashMap<>();
        sources.put("s57-source", Map.of(
                "type", "vector",
                "tiles", List.of("http://localhost:8081/api/s57/tiles/{z}/{x}/{y}.pbf"),
                "minzoom", 0,
                "maxzoom", 14
        ));
        style.put("sources", sources);

        // Layers with styling
        List<Map<String, Object>> layers = new ArrayList<>();

        // Land layer
        layers.add(Map.of(
                "id", "land",
                "type", "fill",
                "source", "s57-source",
                "source-layer", "LNDARE",
                "paint", Map.of(
                        "fill-color", "#d4c5a9",
                        "fill-opacity", 0.8
                )
        ));

        // Depth areas (color by depth)
        layers.add(Map.of(
                "id", "depth-areas-deep",
                "type", "fill",
                "source", "s57-source",
                "source-layer", "DEPARE",
                "filter", List.of(">=", List.of("get", "drval1"), 20),
                "paint", Map.of(
                        "fill-color", "#b3d9ff",
                        "fill-opacity", 0.6
                )
        ));

        layers.add(Map.of(
                "id", "depth-areas-medium",
                "type", "fill",
                "source", "s57-source",
                "source-layer", "DEPARE",
                "filter", List.of("all",
                        List.of(">=", List.of("get", "drval1"), 10),
                        List.of("<", List.of("get", "drval1"), 20)
                ),
                "paint", Map.of(
                        "fill-color", "#80bfff",
                        "fill-opacity", 0.6
                )
        ));

        layers.add(Map.of(
                "id", "depth-areas-shallow",
                "type", "fill",
                "source", "s57-source",
                "source-layer", "DEPARE",
                "filter", List.of("<", List.of("get", "drval1"), 10),
                "paint", Map.of(
                        "fill-color", "#ffcccc",
                        "fill-opacity", 0.7
                )
        ));

        // Coastline
        layers.add(Map.of(
                "id", "coastline",
                "type", "line",
                "source", "s57-source",
                "source-layer", "COALNE",
                "paint", Map.of(
                        "line-color", "#654321",
                        "line-width", 2
                )
        ));

        // Depth contours
        layers.add(Map.of(
                "id", "depth-contours",
                "type", "line",
                "source", "s57-source",
                "source-layer", "DEPCNT",
                "minzoom", 8,
                "paint", Map.of(
                        "line-color", "#4d4dff",
                        "line-width", 1,
                        "line-dasharray", List.of(2, 2)
                )
        ));

        // Soundings
        layers.add(Map.of(
                "id", "soundings",
                "type", "symbol",
                "source", "s57-source",
                "source-layer", "SOUNDG",
                "minzoom", 10,
                "layout", Map.of(
                        "text-field", List.of("get", "depth"),
                        "text-size", 10,
                        "text-font", List.of("Open Sans Regular")
                ),
                "paint", Map.of(
                        "text-color", "#000080",
                        "text-halo-color", "#ffffff",
                        "text-halo-width", 1
                )
        ));

        style.put("layers", layers);

        return ResponseEntity.ok(style);
    }

    /**
     * 5. Health check endpoint
     * GET /api/s57/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "S-57 ENC Chart Service",
                "version", "1.0.0"
        ));
    }
}
