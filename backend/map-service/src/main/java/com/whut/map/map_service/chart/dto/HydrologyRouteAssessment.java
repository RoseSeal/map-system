package com.whut.map.map_service.chart.dto;

public record HydrologyRouteAssessment(
        Double minDepthM,
        boolean crossesShoal,
        Double nearestShoalNm,
        NearestObstructionSummary nearestObstruction,
        int sampleCount,
        int resolvedSampleCount,
        boolean dataComplete
) {
}
