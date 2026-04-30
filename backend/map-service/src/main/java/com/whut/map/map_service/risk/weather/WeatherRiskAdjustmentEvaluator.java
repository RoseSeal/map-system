package com.whut.map.map_service.risk.weather;

import com.whut.map.map_service.risk.config.WeatherRiskProperties;
import com.whut.map.map_service.source.weather.dto.WeatherContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class WeatherRiskAdjustmentEvaluator {

    public static final String REASON_VISIBILITY = "VISIBILITY";
    public static final String REASON_STORM = "STORM";

    private final WeatherRiskProperties properties;

    public WeatherRiskAdjustmentEvaluator(WeatherRiskProperties properties) {
        this.properties = properties;
    }

    public WeatherRiskAdjustment evaluate(WeatherContext weather) {
        List<String> reasons = new ArrayList<>();
        double visibilityScale = computeVisibilityScale(weather, reasons);
        double stormPenalty = computeStormPenalty(weather, reasons);
        return new WeatherRiskAdjustment(visibilityScale, stormPenalty, List.copyOf(reasons));
    }

    private double computeVisibilityScale(WeatherContext weather, List<String> reasons) {
        WeatherRiskProperties.VisibilityConfig visibility = properties.getVisibility();
        if (!visibility.isEnabled() || weather == null || weather.visibilityNm() == null) {
            return 1.0;
        }

        double visibilityNm = weather.visibilityNm();
        if (visibilityNm < visibility.getVeryLowVisNm()) {
            reasons.add(REASON_VISIBILITY);
            return visibility.getScaleVeryLow();
        }
        if (visibilityNm < visibility.getLowVisNm()) {
            reasons.add(REASON_VISIBILITY);
            return visibility.getScaleLow();
        }
        return 1.0;
    }

    private double computeStormPenalty(WeatherContext weather, List<String> reasons) {
        WeatherRiskProperties.StormConfig storm = properties.getStorm();
        if (!storm.isEnabled() || weather == null) {
            return 0.0;
        }

        boolean stormCode = "STORM".equalsIgnoreCase(weather.weatherCode());
        boolean highSeaState = weather.seaState() != null && weather.seaState() >= storm.getSeaStateThreshold();
        if (stormCode || highSeaState) {
            reasons.add(REASON_STORM);
            return storm.getPenaltyScore();
        }
        return 0.0;
    }
}
