package com.whut.map.map_service.risk.hydrology;

import java.util.List;

public record HydrologyRiskAdjustment(
        double penaltyScore,
        List<String> reasons
) {
    public static HydrologyRiskAdjustment zero() {
        return new HydrologyRiskAdjustment(0.0, List.of());
    }
}
