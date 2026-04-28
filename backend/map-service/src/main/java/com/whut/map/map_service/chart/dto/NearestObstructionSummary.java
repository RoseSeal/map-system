package com.whut.map.map_service.chart.dto;

public record NearestObstructionSummary(
        String category,
        Double distanceNm,
        Integer bearingDeg
) {
}
