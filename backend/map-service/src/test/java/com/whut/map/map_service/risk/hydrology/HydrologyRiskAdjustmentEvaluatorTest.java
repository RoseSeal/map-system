package com.whut.map.map_service.risk.hydrology;

import com.whut.map.map_service.chart.dto.HydrologyContext;
import com.whut.map.map_service.chart.dto.NearestObstructionSummary;
import com.whut.map.map_service.risk.config.HydrologyRiskProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HydrologyRiskAdjustmentEvaluatorTest {

    @Test
    void disabledPropertiesReturnZeroPenalty() {
        HydrologyRiskAdjustmentEvaluator evaluator = new HydrologyRiskAdjustmentEvaluator(new HydrologyRiskProperties());

        HydrologyRiskAdjustment adjustment = evaluator.evaluate(
                new HydrologyContext(8.3, 0.0, new NearestObstructionSummary("WRECK", 0.1, 37)),
                10.0
        );

        assertThat(adjustment.penaltyScore()).isZero();
        assertThat(adjustment.reasons()).isEmpty();
    }

    @Test
    void depthBelowSafetyContourProducesShoalDepthReason() {
        HydrologyRiskAdjustmentEvaluator evaluator = new HydrologyRiskAdjustmentEvaluator(enabledProperties());

        HydrologyRiskAdjustment adjustment = evaluator.evaluate(new HydrologyContext(8.3, null, null), 10.0);

        assertThat(adjustment.penaltyScore()).isEqualTo(0.12);
        assertThat(adjustment.reasons()).contains(HydrologyRiskAdjustmentEvaluator.REASON_SHOAL_DEPTH);
    }

    @Test
    void shoalProximityPenaltyDecreasesWithDistance() {
        HydrologyRiskAdjustmentEvaluator evaluator = new HydrologyRiskAdjustmentEvaluator(enabledProperties());

        double near = evaluator.evaluate(new HydrologyContext(null, 0.1, null), 10.0).penaltyScore();
        double far = evaluator.evaluate(new HydrologyContext(null, 0.4, null), 10.0).penaltyScore();
        double boundary = evaluator.evaluate(new HydrologyContext(null, 0.5, null), 10.0).penaltyScore();

        assertThat(near).isGreaterThan(far);
        assertThat(far).isGreaterThan(boundary);
        assertThat(boundary).isZero();
    }

    @Test
    void obstructionBeyondInfluenceDoesNotProducePenalty() {
        HydrologyRiskAdjustmentEvaluator evaluator = new HydrologyRiskAdjustmentEvaluator(enabledProperties());

        HydrologyRiskAdjustment adjustment = evaluator.evaluate(
                new HydrologyContext(null, null, new NearestObstructionSummary("WRECK", 0.8, 37)),
                10.0
        );

        assertThat(adjustment.penaltyScore()).isZero();
        assertThat(adjustment.reasons()).doesNotContain(HydrologyRiskAdjustmentEvaluator.REASON_OBSTRUCTION_PROXIMITY);
    }

    @Test
    void combinedPenaltyIsClampedToConfiguredMaximum() {
        HydrologyRiskAdjustmentEvaluator evaluator = new HydrologyRiskAdjustmentEvaluator(enabledProperties());

        HydrologyRiskAdjustment adjustment = evaluator.evaluate(
                new HydrologyContext(8.3, 0.0, new NearestObstructionSummary("WRECK", 0.0, 37)),
                10.0
        );

        assertThat(adjustment.penaltyScore()).isEqualTo(0.20);
        assertThat(adjustment.reasons()).contains(
                HydrologyRiskAdjustmentEvaluator.REASON_SHOAL_DEPTH,
                HydrologyRiskAdjustmentEvaluator.REASON_SHOAL_PROXIMITY,
                HydrologyRiskAdjustmentEvaluator.REASON_OBSTRUCTION_PROXIMITY
        );
    }

    private HydrologyRiskProperties enabledProperties() {
        HydrologyRiskProperties properties = new HydrologyRiskProperties();
        properties.setEnabled(true);
        return properties;
    }
}
