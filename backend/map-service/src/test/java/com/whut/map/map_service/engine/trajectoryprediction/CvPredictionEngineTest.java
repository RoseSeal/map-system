package com.whut.map.map_service.engine.trajectoryprediction;

import com.whut.map.map_service.config.properties.TrajectoryPredictionProperties;
import com.whut.map.map_service.domain.QualityFlag;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

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

        CvPredictionResult result = engine.consume(ship, List.of(ship));

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

        CvPredictionResult result = engine.consume(ship, List.of(ship));

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

        CvPredictionResult result = engine.consume(ship, List.of(ship));

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

        CvPredictionResult result = engine.consume(ship, List.of(ship));

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNegativeSog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(-1.0)
                .build();

        CvPredictionResult result = engine.consume(ship, List.of(ship));

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNaN_Sog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(Double.NaN)
                .build();

        CvPredictionResult result = engine.consume(ship, List.of(ship));

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictSentinelCog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(8.0)
                .cog(360.0)
                .build();

        CvPredictionResult result = engine.consume(ship, List.of(ship));

        assertThat(result.getTrajectory()).isEmpty();
    }

    @Test
    void predictNaN_Cog() {
        ShipStatus ship = ShipStatus.builder()
                .id("123")
                .sog(8.0)
                .cog(Double.NaN)
                .build();

        CvPredictionResult result = engine.consume(ship, List.of(ship));

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

        CvPredictionResult result = engine.consume(ship, List.of(ship));

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
        CvPredictionResult result = engine.consume(ship, null);
        Instant end = Instant.now();

        assertThat(result.getPredictionTime()).isBetween(start, end);
    }

    @Test
    void predictUsesCtrWhenRotAboveThreshold() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        OffsetDateTime t1 = t0.plusSeconds(30);
        OffsetDateTime t2 = t0.plusSeconds(60);

        // Build +6 deg/min ROT history (84 -> 87 -> 90 deg over 60s).
        ShipStatus h0 = ShipStatus.builder().id("123").msgTime(t0).cog(84.0).build();
        ShipStatus h1 = ShipStatus.builder().id("123").msgTime(t1).cog(87.0).build();
        ShipStatus current = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
            .cog(90.0)
                .msgTime(t2)
                .build();

        CvPredictionResult result = engine.consume(current, List.of(h0, h1, current));

        CvPredictionResult.PredictedPoint first = result.getTrajectory().get(0);
        CvPredictionResult.PredictedPoint second = result.getTrajectory().get(1);

        double[] cvVelocity = GeoUtils.toVelocity(current.getSog(), current.getCog());
        double[] cvSecond = GeoUtils.displace(current.getLatitude(), current.getLongitude(), cvVelocity[0] * 60, cvVelocity[1] * 60);

        // Under CTR (right turn from 90 deg toward 93 deg at step 2), latitude bends south;
        // pure CV at 90 deg keeps latitude unchanged.
        assertThat(second.getLatitude()).isLessThan(first.getLatitude());
        assertThat(second.getLatitude()).isLessThan(cvSecond[0]);
    }

    @Test
    void predictFallsBackToCvWhenRotBelowThreshold() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        OffsetDateTime t1 = t0.plusSeconds(30);
        OffsetDateTime t2 = t0.plusSeconds(60);

        ShipStatus h0 = ShipStatus.builder().id("123").msgTime(t0).cog(0.0).build();
        ShipStatus h1 = ShipStatus.builder().id("123").msgTime(t1).cog(0.2).build();
        ShipStatus current = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(0.4)
                .msgTime(t2)
                .build();

        CvPredictionResult result = engine.consume(current, List.of(h0, h1, current));

            CvPredictionResult.PredictedPoint p1 = result.getTrajectory().get(0);
        CvPredictionResult.PredictedPoint p2 = result.getTrajectory().get(1);
        double[] velocity = GeoUtils.toVelocity(current.getSog(), current.getCog());
            double[] cvP1 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 30, velocity[1] * 30);
        double[] cvP2 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 60, velocity[1] * 60);
            assertThat(p1.getLongitude()).isCloseTo(cvP1[1], within(1e-9));
            assertThat(p1.getLatitude()).isCloseTo(cvP1[0], within(1e-9));
        assertThat(p2.getLongitude()).isCloseTo(cvP2[1], within(1e-9));
        assertThat(p2.getLatitude()).isCloseTo(cvP2[0], within(1e-9));
    }

    @Test
    void predictUnwrapsCogAcrossZeroForPositiveRot() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        OffsetDateTime t1 = t0.plusSeconds(30);
        OffsetDateTime t2 = t0.plusSeconds(60);

        ShipStatus h0 = ShipStatus.builder().id("123").msgTime(t0).cog(359.0).build();
        ShipStatus h1 = ShipStatus.builder().id("123").msgTime(t1).cog(1.0).build();
        ShipStatus current = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(3.0)
                .msgTime(t2)
                .build();

        CvPredictionResult result = engine.consume(current, List.of(h0, h1, current));

        CvPredictionResult.PredictedPoint first = result.getTrajectory().get(0);
        CvPredictionResult.PredictedPoint second = result.getTrajectory().get(1);
        assertThat(second.getLongitude()).isGreaterThan(first.getLongitude());
    }

    @Test
    void predictIgnoresHistoryPointsFlaggedAsCogJump() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        OffsetDateTime t1 = t0.plusSeconds(30);
        OffsetDateTime t2 = t0.plusSeconds(60);

        ShipStatus h0 = ShipStatus.builder().id("123").msgTime(t0).cog(84.0).build();
        ShipStatus h1 = ShipStatus.builder()
                .id("123")
                .msgTime(t1)
                .cog(87.0)
                .qualityFlags(Set.of(QualityFlag.COG_JUMP))
                .build();
        ShipStatus current = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .msgTime(t2)
                .build();

        CvPredictionResult result = engine.consume(current, List.of(h0, h1, current));

        CvPredictionResult.PredictedPoint p1 = result.getTrajectory().get(0);
        CvPredictionResult.PredictedPoint p2 = result.getTrajectory().get(1);
        double[] velocity = GeoUtils.toVelocity(current.getSog(), current.getCog());
        double[] cvP1 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 30, velocity[1] * 30);
        double[] cvP2 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 60, velocity[1] * 60);

        assertThat(p1.getLongitude()).isCloseTo(cvP1[1], within(1e-9));
        assertThat(p1.getLatitude()).isCloseTo(cvP1[0], within(1e-9));
        assertThat(p2.getLongitude()).isCloseTo(cvP2[1], within(1e-9));
        assertThat(p2.getLatitude()).isCloseTo(cvP2[0], within(1e-9));
    }

    @Test
    void predictIgnoresHistoryPointsFlaggedAsPositionJump() {
        OffsetDateTime t0 = OffsetDateTime.parse("2026-04-12T09:00:00+08:00");
        OffsetDateTime t1 = t0.plusSeconds(30);
        OffsetDateTime t2 = t0.plusSeconds(60);

        ShipStatus h0 = ShipStatus.builder().id("123").msgTime(t0).cog(84.0).build();
        ShipStatus h1 = ShipStatus.builder()
                .id("123")
                .msgTime(t1)
                .cog(87.0)
                .qualityFlags(Set.of(QualityFlag.POSITION_JUMP))
                .build();
        ShipStatus current = ShipStatus.builder()
                .id("123")
                .longitude(114.0)
                .latitude(30.0)
                .sog(8.0)
                .cog(90.0)
                .msgTime(t2)
                .build();

        CvPredictionResult result = engine.consume(current, List.of(h0, h1, current));

        CvPredictionResult.PredictedPoint p1 = result.getTrajectory().get(0);
        CvPredictionResult.PredictedPoint p2 = result.getTrajectory().get(1);
        double[] velocity = GeoUtils.toVelocity(current.getSog(), current.getCog());
        double[] cvP1 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 30, velocity[1] * 30);
        double[] cvP2 = GeoUtils.displace(current.getLatitude(), current.getLongitude(), velocity[0] * 60, velocity[1] * 60);

        assertThat(p1.getLongitude()).isCloseTo(cvP1[1], within(1e-9));
        assertThat(p1.getLatitude()).isCloseTo(cvP1[0], within(1e-9));
        assertThat(p2.getLongitude()).isCloseTo(cvP2[1], within(1e-9));
        assertThat(p2.getLatitude()).isCloseTo(cvP2[0], within(1e-9));
    }
}
