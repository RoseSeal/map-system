package com.whut.map.map_service.risk.weather;

import java.util.List;

public record WeatherRiskAdjustment(
        double visibilityScale,
        double stormPenalty,
        List<String> reasons
) {

    public boolean active() {
        return !reasons.isEmpty();
    }
}
