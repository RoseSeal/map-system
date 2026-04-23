package com.whut.map.map_service.risk.engine.collision;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PredictedCpaTcpaResult {

    private String targetMmsi;
    private double cpaDistanceMeters;
    private double tcpaSeconds;

    /**
     * Metadata for future risk consumers. The graphic CPA line is still gated by
     * TargetRiskAssessment.approaching, not by this field.
     */
    private boolean approaching;
    private double ownCpaLatitude;
    private double ownCpaLongitude;
    private double targetCpaLatitude;
    private double targetCpaLongitude;
}
