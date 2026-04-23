package com.whut.map.map_service.risk.engine.collision;

import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.AisProtocolConstants;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PredictedCpaTcpaCalculator {

    public PredictedCpaTcpaResult calculate(ShipStatus ownShip, CvPredictionResult targetPrediction) {
        if (ownShip == null || targetPrediction == null) {
            return null;
        }

        List<CvPredictionResult.PredictedPoint> trajectory = targetPrediction.getTrajectory();
        if (trajectory == null || trajectory.isEmpty()) {
            return null;
        }

        double ownSog = ownShip.getSog();
        double ownCog = ownShip.getCog();
        if (!AisProtocolConstants.isValidSog(ownSog) || !AisProtocolConstants.isValidCog(ownCog)) {
            return null;
        }

        double[] ownVelocity = GeoUtils.toVelocity(ownSog, ownCog);
        PredictedCpaSample sample = findMinDistanceSample(
                ownShip.getLatitude(),
                ownShip.getLongitude(),
                ownVelocity[0],
                ownVelocity[1],
                trajectory
        );
        if (sample == null) {
            return null;
        }

        return PredictedCpaTcpaResult.builder()
                .targetMmsi(targetPrediction.getTargetId())
                .cpaDistanceMeters(sample.distanceMeters())
                .tcpaSeconds(sample.offsetSeconds())
                .approaching(sample.offsetSeconds() > 0)
                .ownCpaLatitude(sample.ownLatitude())
                .ownCpaLongitude(sample.ownLongitude())
                .targetCpaLatitude(sample.targetLatitude())
                .targetCpaLongitude(sample.targetLongitude())
                .build();
    }

    private PredictedCpaSample findMinDistanceSample(
            double ownLatitude,
            double ownLongitude,
            double ownVelocityX,
            double ownVelocityY,
            List<CvPredictionResult.PredictedPoint> trajectory
    ) {
        PredictedCpaSample best = null;

        for (CvPredictionResult.PredictedPoint point : trajectory) {
            if (point == null || point.getOffsetSeconds() < 0) {
                continue;
            }

            int offsetSeconds = point.getOffsetSeconds();
            double[] ownExtrapolated = GeoUtils.displace(
                    ownLatitude,
                    ownLongitude,
                    ownVelocityX * offsetSeconds,
                    ownVelocityY * offsetSeconds
            );
            double distanceMeters = GeoUtils.distanceMetersByXY(
                    ownExtrapolated[0],
                    ownExtrapolated[1],
                    point.getLatitude(),
                    point.getLongitude()
            );

            if (best == null || distanceMeters < best.distanceMeters()) {
                best = new PredictedCpaSample(
                        distanceMeters,
                        offsetSeconds,
                        ownExtrapolated[0],
                        ownExtrapolated[1],
                        point.getLatitude(),
                        point.getLongitude()
                );
            }
        }

        return best;
    }

    private record PredictedCpaSample(
            double distanceMeters,
            int offsetSeconds,
            double ownLatitude,
            double ownLongitude,
            double targetLatitude,
            double targetLongitude
    ) {
    }
}
