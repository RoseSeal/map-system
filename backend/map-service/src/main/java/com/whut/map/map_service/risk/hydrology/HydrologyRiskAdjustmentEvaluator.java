package com.whut.map.map_service.risk.hydrology;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.risk.config.HydrologyRiskProperties;
import com.whut.map.map_service.shared.util.MathUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class HydrologyRiskAdjustmentEvaluator {

    public static final String REASON_SHOAL_DEPTH = "SHOAL_DEPTH";
    public static final String REASON_SHOAL_PROXIMITY = "SHOAL_PROXIMITY";
    public static final String REASON_OBSTRUCTION_PROXIMITY = "OBSTRUCTION_PROXIMITY";

    private final HydrologyRiskProperties properties;

    public HydrologyRiskAdjustmentEvaluator(HydrologyRiskProperties properties) {
        this.properties = properties;
    }

    public HydrologyRiskAdjustment evaluate(HydrologyContext hydrology, double safetyContourMeters) {
        if (!properties.isEnabled() || hydrology == null || !Double.isFinite(safetyContourMeters)) {
            return HydrologyRiskAdjustment.zero();
        }

        double shoalPenalty = 0.0;
        double obstructionPenalty = 0.0;
        List<String> reasons = new ArrayList<>();

        if (hydrology.ownShipMinDepthM() != null && hydrology.ownShipMinDepthM() < safetyContourMeters) {
            shoalPenalty = properties.getShoalMaxPenaltyScore();
            reasons.add(REASON_SHOAL_DEPTH);
        }

        if (hydrology.nearestShoalNm() != null) {
            double proximityPenalty = distancePenalty(
                    hydrology.nearestShoalNm(),
                    properties.getShoalInfluenceNm(),
                    properties.getShoalMaxPenaltyScore()
            );
            if (proximityPenalty > 0.0) {
                shoalPenalty = Math.max(shoalPenalty, proximityPenalty);
                reasons.add(REASON_SHOAL_PROXIMITY);
            }
        }

        NearestObstructionSummary obstruction = hydrology.nearestObstruction();
        if (obstruction != null && obstruction.distanceNm() != null) {
            obstructionPenalty = distancePenalty(
                    obstruction.distanceNm(),
                    properties.getObstructionInfluenceNm(),
                    properties.getObstructionMaxPenaltyScore()
            );
            if (obstructionPenalty > 0.0) {
                reasons.add(REASON_OBSTRUCTION_PROXIMITY);
            }
        }

        double maxPenalty = properties.getShoalMaxPenaltyScore() + properties.getObstructionMaxPenaltyScore();
        double penalty = MathUtils.clamp(shoalPenalty + obstructionPenalty, 0.0, maxPenalty);
        return new HydrologyRiskAdjustment(penalty, List.copyOf(reasons));
    }

    private double distancePenalty(double distanceNm, double influenceNm, double maxPenalty) {
        if (!Double.isFinite(distanceNm) || !Double.isFinite(influenceNm)
                || !Double.isFinite(maxPenalty) || influenceNm <= 0.0 || maxPenalty <= 0.0) {
            return 0.0;
        }
        if (distanceNm < 0.0 || distanceNm > influenceNm) {
            return 0.0;
        }
        double ratio = 1.0 - MathUtils.clamp(distanceNm / influenceNm, 0.0, 1.0);
        return maxPenalty * ratio;
    }
}
