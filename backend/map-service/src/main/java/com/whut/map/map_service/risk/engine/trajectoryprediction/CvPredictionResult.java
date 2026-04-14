package com.whut.map.map_service.risk.engine.trajectoryprediction;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data @Builder
public class CvPredictionResult {
    private String targetId;
    private List<PredictedPoint> trajectory;
    private Instant predictionTime;
    private int horizonSeconds;

    @Data @Builder
    public static class PredictedPoint {
        private double latitude;
        private double longitude;
        private int offsetSeconds;
    }
}
