package com.whut.map.map_service.risk.engine.collision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CpaTcpaResult {

    private String targetMmsi; // 目标船舶的MMSI
    private double cpaDistance; // CPA距离
    private double tcpaTime;    // TCPA时间
    private boolean isApproaching; // TCPA > 0 表示正在接近
    /**
     * True when relative motion is large enough for a meaningful CPA/TCPA calculation.
     * False when relative speed is below MIN_RELATIVE_SPEED_MS (parallel / stationary ships):
     * in that case tcpaTime is a sentinel (0) and must not be used for risk classification.
     */
    @Builder.Default
    private boolean cpaValid = true;

}