package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PredictedCpaCalculatorTest {

    private final PredictedCpaCalculator calculator = new PredictedCpaCalculator();

    @Test
    void shouldCalculateMinDistanceInTrajectory() {
        // Own ship at (30.0, 120.0)
        ShipStatus own = ShipStatus.builder()
                .latitude(30.0).longitude(120.0)
                .sog(0.0).cog(0.0) // stationary own ship for simplicity
                .build();

        // Target prediction: moving South
        CvPredictionResult prediction = CvPredictionResult.builder()
                .targetId("target-1")
                .trajectory(List.of(
                        point(30.01, 120.0, 0),    // t=0, dist ~ 0.6nm
                        point(30.005, 120.0, 30),  // t=30, dist ~ 0.3nm
                        point(30.001, 120.0, 60)   // t=60, dist ~ 0.06nm (CPA)
                ))
                .build();

        double[] result = calculator.calculate(own, prediction);
        
        assertThat(result).isNotNull();
        assertThat(result[0]).isLessThan(0.1); // predicted CPA distance in NM
        assertThat(result[1]).isEqualTo(60.0); // predicted TCPA in sec
    }

    @Test
    void shouldReturnNullWhenPredictionMissing() {
        ShipStatus own = ShipStatus.builder().latitude(30).longitude(120).sog(10).cog(90).build();
        assertThat(calculator.calculate(own, null)).isNull();
        assertThat(calculator.calculate(own, CvPredictionResult.builder().trajectory(List.of()).build())).isNull();
    }

    private CvPredictionResult.PredictedPoint point(double lat, double lon, int offset) {
        return CvPredictionResult.PredictedPoint.builder()
                .latitude(lat).longitude(lon).offsetSeconds(offset)
                .build();
    }
}
