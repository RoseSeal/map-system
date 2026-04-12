package com.whut.map.map_service.engine.trajectoryprediction;

import com.whut.map.map_service.config.properties.TrajectoryPredictionProperties;
import com.whut.map.map_service.domain.ShipStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CvPredictionEngineTest {

    private TrajectoryPredictionProperties props;
    private CvPredictionEngine engine;

    @BeforeEach
    void setUp() {
        props = new TrajectoryPredictionProperties();
        props.setHorizonSeconds(60);
        props.setStepSeconds(30);
        engine = new CvPredictionEngine(props);
    }

    @Test
    void predictNorthward() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0) // 8 knots
                .cog(0.0) // North
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTargetId()).isEqualTo("123");
        assertThat(result.getTrajectory()).hasSize(2);
        
        CvPredictionResult.PredictedPoint p1 = result.getTrajectory().get(0);
        assertThat(p1.getOffsetSeconds()).isEqualTo(30);
        assertThat(p1.getLongitude()).isEqualTo(114.0);
        assertThat(p1.getLatitude()).isGreaterThan(30.0); // Moved north

        CvPredictionResult.PredictedPoint p2 = result.getTrajectory().get(1);
        assertThat(p2.getOffsetSeconds()).isEqualTo(60);
        assertThat(p2.getLatitude()).isGreaterThan(p1.getLatitude()); // Kept moving north
    }

    @Test
    void predictEastward() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0) // East
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).hasSize(2);
        
        CvPredictionResult.PredictedPoint p1 = result.getTrajectory().get(0);
        assertThat(p1.getLatitude()).isEqualTo(30.0);
        assertThat(p1.getLongitude()).isGreaterThan(114.0); // Moved east
    }

    @Test
    void predictStationary() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(0.0)
                .cog(45.0)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).hasSize(2);
        assertThat(result.getTrajectory().get(0).getLongitude()).isEqualTo(114.0);
        assertThat(result.getTrajectory().get(0).getLatitude()).isEqualTo(30.0);
    }

    @Test
    void predictSentinelSog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(102.3)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNegativeSog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(-1.0)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNaN_Sog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(Double.NaN)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictSentinelCog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(8.0)
                .cog(360.0)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNaN_Cog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(8.0)
                .cog(Double.NaN)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictionTimeSourceMsgTime() {
        OffsetDateTime time = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(102.3) // Empty trajectory is fine
                .msgTime(time)
                .build();

        CvPredictionResult result = engine.consume(ship);

        assertThat(result.getPredictionTime()).isEqualTo(time.toInstant());
    }

    @Test
    void predictionTimeSourceFallback() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(102.3)
                .msgTime(null)
                .build();

        Instant start = Instant.now();
        CvPredictionResult result = engine.consume(ship);
        Instant end = Instant.now();

        assertThat(result.getPredictionTime()).isBetween(start, end);
    }
}
