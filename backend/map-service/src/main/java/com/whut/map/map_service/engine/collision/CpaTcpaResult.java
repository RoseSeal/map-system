package com.whut.map.map_service.engine.collision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CpaTcpaResult {

    private String targetMmsi; // 目标船舶的MMSI
    private double cpaDistance; // CPA距离
    private double tcpaTime;    // TCPA时间
    private boolean isApproaching; // TCPA > 0 表示正在接近

}