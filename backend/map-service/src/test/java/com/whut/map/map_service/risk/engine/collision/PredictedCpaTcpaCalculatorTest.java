package com.whut.map.map_service.risk.engine.collision;

import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PredictedCpaTcpaCalculatorTest {

    private final PredictedCpaTcpaCalculator calculator = new PredictedCpaTcpaCalculator();

    @Test
    void calculateReturnsMatchedTrajectorySampleAndOwnProjectedPoint() {
        ShipStatus ownShip = ShipStatus.builder()
                .id("own")
                .latitude(30.0)
                .longitude(120.0)
                .sog(0.0)
                .cog(0.0)
                .build();
        CvPredictionResult prediction = CvPredictionResult.builder()
                .targetId("target-1")
                .trajectory(List.of(
                        point(30.01, 120.0, 0),
                        point(30.005, 120.0, 30),
                        point(30.001, 120.0, 60)
                ))
                .build();

        PredictedCpaTcpaResult result = calculator.calculate(ownShip, prediction);

        assertThat(result).isNotNull();
        assertThat(result.getTargetMmsi()).isEqualTo("target-1");
        assertThat(result.getTcpaSeconds()).isEqualTo(60.0);
        assertThat((int) result.getTcpaSeconds()).isEqualTo(60);
        assertThat(result.getCpaDistanceMeters()).isPositive();
        assertThat(result.getOwnCpaLatitude()).isEqualTo(30.0);
        assertThat(result.getOwnCpaLongitude()).isEqualTo(120.0);
        assertThat(result.getTargetCpaLatitude()).isEqualTo(30.001);
        assertThat(result.getTargetCpaLongitude()).isEqualTo(120.0);
    }

    @Test
    void calculateReturnsNullWhenPredictionIsUnavailable() {
        ShipStatus ownShip = ShipStatus.builder()
                .latitude(30.0)
                .longitude(120.0)
                .sog(8.0)
                .cog(90.0)
                .build();

        assertThat(calculator.calculate(ownShip, null)).isNull();
        assertThat(calculator.calculate(ownShip, CvPredictionResult.builder().trajectory(List.of()).build())).isNull();
    }

    private CvPredictionResult.PredictedPoint point(double lat, double lon, int offsetSeconds) {
        return CvPredictionResult.PredictedPoint.builder()
                .latitude(lat)
                .longitude(lon)
                .offsetSeconds(offsetSeconds)
                .build();
    }
}
