package com.whut.map.map_service.service.s57;

import com.whut.map.map_service.repository.S57TileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Service for S-57 ENC Chart tile generation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class S57TileService {

    private final S57TileRepository s57TileRepository;

    /**
     * Get MVT tile for S-57 layer
     */
    public byte[] getTile(String layerName, int z, int x, int y) {
        return s57TileRepository.getTile(layerName, z, x, y);
    }

    /**
     * Get MVT tile with safety contour filtering
     */
    public byte[] getTile(String layerName, int z, int x, int y, Double safetyContour) {
        return s57TileRepository.getTile(layerName, z, x, y, safetyContour);
    }

    /**
     * Get composite MVT tile containing all S-57 layers
     * Uses database-level MVT concatenation for proper merging
     */
    public byte[] getCompositeTile(int z, int x, int y, Double safetyContour) {
        return s57TileRepository.getCompositeTile(z, x, y, safetyContour);
    }

    /**
     * Check if layer is supported
     */
    public boolean isLayerSupported(String layerName) {
        return s57TileRepository.isLayerSupported(layerName);
    }
}
