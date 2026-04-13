package com.whut.map.map_service.engine.trajectoryprediction;

import com.whut.map.map_service.config.properties.TrajectoryPredictionProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CvPredictionEngine {

    private static final double AIS_SOG_NOT_AVAILABLE_KN = 102.3;
    private static final double EPS = 1e-9;
    private final TrajectoryPredictionProperties props;

    private CvPredictionResult predict(ShipStatus ship, List<ShipStatus> history) {
        Instant predictionTime = (ship.getMsgTime() != null)
                ? ship.getMsgTime().toInstant()
                : Instant.now();

        double sog = ship.getSog();
        double cog = ship.getCog();

        boolean invalidSog = Double.isNaN(sog) || sog < 0 || sog >= AIS_SOG_NOT_AVAILABLE_KN;
        boolean invalidCog = Double.isNaN(cog) || cog < 0 || cog >= 360.0;

        if (invalidSog || (sog > 0 && invalidCog)) {
            return CvPredictionResult.builder()
                    .targetId(ship.getId())
                    .trajectory(Collections.emptyList())
                    .predictionTime(predictionTime)
                    .horizonSeconds(props.getHorizonSeconds())
                    .build();
        }

        // Use safe COG for stationary vessel (cog is ignored anyway when sog=0, but prevent NaN in toVelocity)
        double safeCog = invalidCog ? 0.0 : cog;
        int step = props.getStepSeconds();
        int horizon = props.getHorizonSeconds();
        double rotDegPerSec = extractRotDegPerSec(history);

        List<CvPredictionResult.PredictedPoint> points = new ArrayList<>();
        if (Math.abs(rotDegPerSec) < EPS) {
            double[] velocity = GeoUtils.toVelocity(sog, safeCog);
            for (int t = step; t <= horizon; t += step) {
                double[] latLon = GeoUtils.displace(
                        ship.getLatitude(), ship.getLongitude(),
                        velocity[0] * t, velocity[1] * t
                );
                points.add(CvPredictionResult.PredictedPoint.builder()
                        .latitude(latLon[0])
                        .longitude(latLon[1])
                        .offsetSeconds(t)
                        .build());
            }
        } else {
            double headingDeg = safeCog;
            double lat = ship.getLatitude();
            double lon = ship.getLongitude();

            for (int t = step; t <= horizon; t += step) {
                // Forward Euler: displace with current heading, then advance heading.
                double[] velocity = GeoUtils.toVelocity(sog, headingDeg);
                double[] latLon = GeoUtils.displace(lat, lon, velocity[0] * step, velocity[1] * step);
                lat = latLon[0];
                lon = latLon[1];
                points.add(CvPredictionResult.PredictedPoint.builder()
                        .latitude(lat)
                        .longitude(lon)
                        .offsetSeconds(t)
                        .build());
                headingDeg = normalizeAngleDeg(headingDeg + rotDegPerSec * step);
            }
        }

        return CvPredictionResult.builder()
                .targetId(ship.getId())
                .trajectory(points)
                .predictionTime(predictionTime)
                .horizonSeconds(horizon)
                .build();
    }

    public CvPredictionResult consume(ShipStatus message, List<ShipStatus> history) {
        log.debug("CV prediction for target MMSI: {}", message.getId());
        return predict(message, history);
    }

    private double extractRotDegPerSec(List<ShipStatus> history) {
        if (history == null || history.isEmpty()) {
            return 0.0;
        }

        List<ShipStatus> valid = history.stream()
                .filter(this::isValidForRot)
                .sorted(Comparator.comparing(s -> s.getMsgTime().toInstant()))
                .toList();

        if (valid.size() < 3) {
            return 0.0;
        }

        Instant baseTime = valid.get(0).getMsgTime().toInstant();
        int n = valid.size();
        double[] xs = new double[n];
        double[] ys = new double[n];

        double prevRawCog = valid.get(0).getCog();
        double prevUnwrappedCog = prevRawCog;

        xs[0] = 0.0;
        ys[0] = prevUnwrappedCog;

        for (int i = 1; i < n; i += 1) {
            ShipStatus point = valid.get(i);
            double rawCog = point.getCog();
            double delta = rawCog - prevRawCog;
            if (delta > 180.0) {
                delta -= 360.0;
            } else if (delta < -180.0) {
                delta += 360.0;
            }
            prevUnwrappedCog += delta;
            prevRawCog = rawCog;

            xs[i] = Duration.between(baseTime, point.getMsgTime().toInstant()).toMillis() / 1000.0;
            ys[i] = prevUnwrappedCog;
        }

        double rotDegPerSec = linearRegressionSlope(xs, ys);
        if (Math.abs(rotDegPerSec) * 60.0 < props.getRotThresholdDegPerMin()) {
            return 0.0;
        }
        return rotDegPerSec;
    }

    private boolean isValidForRot(ShipStatus point) {
        if (point == null || point.getMsgTime() == null) {
            return false;
        }
        double cog = point.getCog();
        return !Double.isNaN(cog) && cog >= 0.0 && cog < 360.0;
    }

    private double linearRegressionSlope(double[] xs, double[] ys) {
        int n = xs.length;
        if (n < 2) {
            return 0.0;
        }

        double xMean = 0.0;
        double yMean = 0.0;
        for (int i = 0; i < n; i += 1) {
            xMean += xs[i];
            yMean += ys[i];
        }
        xMean /= n;
        yMean /= n;

        double numerator = 0.0;
        double denominator = 0.0;
        for (int i = 0; i < n; i += 1) {
            double dx = xs[i] - xMean;
            numerator += dx * (ys[i] - yMean);
            denominator += dx * dx;
        }

        return denominator < EPS ? 0.0 : numerator / denominator;
    }

    private double normalizeAngleDeg(double angleDeg) {
        double normalized = angleDeg % 360.0;
        return normalized < 0 ? normalized + 360.0 : normalized;
    }
}
