package com.whut.map.map_service.engine.encounter;

import lombok.Builder;
import lombok.Data;

/**
 * Result of encounter classification for a target ship.
 */
@Data
@Builder
public class EncounterClassificationResult {
    private String targetId;
    private EncounterType encounterType;
    /** 目标相对本船船头的方位角，[0, 360)，顺时针，以本船 COG 为参考轴。 */
    private double relativeBearingDeg;
    /** 两船航向的最小角度差，[0, 180]。 */
    private double courseDifferenceDeg;
}
