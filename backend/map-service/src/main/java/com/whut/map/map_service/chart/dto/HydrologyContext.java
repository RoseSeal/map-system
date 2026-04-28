package com.whut.map.map_service.chart.dto;

public record HydrologyContext(
        Double ownShipMinDepthM,
        Double nearestShoalNm,
        NearestObstructionSummary nearestObstruction
) {
}
