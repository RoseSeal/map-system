package com.whut.map.map_service.pipeline.assembler;

public final class AssemblerProtocolConstants {

    private AssemblerProtocolConstants() {
    }

    // prediction_type values
    public static final String PREDICTION_TYPE_CV = "cv";
    public static final String PREDICTION_TYPE_LINEAR = "linear";

    // tracking_status values
    public static final String TRACKING_STATUS_TRACKING = "tracking";
    public static final String TRACKING_STATUS_STALE = "stale";

    // platform_health status values
    public static final String HEALTH_STATUS_NORMAL = "NORMAL";
    public static final String HEALTH_STATUS_DEGRADED = "DEGRADED";
}