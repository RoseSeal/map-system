package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.util.AisProtocolConstants;
import com.whut.map.map_service.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PredictedCpaCalculator {

    public PredictedCpaCalculator() {
    }

    /**
     * @return {predictedCpaNm, predictedTcpaSec}，或 null 如果无法计算
     */
    public double[] calculate(ShipStatus ownShip, CvPredictionResult targetPrediction) {
        if (ownShip == null || targetPrediction == null) {
            return null;
        }

        List<CvPredictionResult.PredictedPoint> trajectory = targetPrediction.getTrajectory();
        if (trajectory == null || trajectory.isEmpty()) {
            return null;
        }

        double ownSog = ownShip.getSog();
        double ownCog = ownShip.getCog();
        if (isOwnShipMotionInvalid(ownSog, ownCog)) {
            return null;
        }

        double[] ownVelocity = GeoUtils.toVelocity(ownSog, ownCog);
        double[] minDistanceAndTcpa = findMinDistanceAndTcpa(
                ownShip.getLatitude(),
                ownShip.getLongitude(),
                ownVelocity[0],
                ownVelocity[1],
                trajectory
        );

        return new double[]{GeoUtils.metersToNm(minDistanceAndTcpa[0]), minDistanceAndTcpa[1]};
    }

    private boolean isOwnShipMotionInvalid(double ownSog, double ownCog) {
        return !AisProtocolConstants.isValidSog(ownSog)
            || !AisProtocolConstants.isValidCog(ownCog);
    }

    private double[] findMinDistanceAndTcpa(
            double ownLatitude,
            double ownLongitude,
            double ownVelocityX,
            double ownVelocityY,
            List<CvPredictionResult.PredictedPoint> trajectory
    ) {
        double minDistanceMeters = Double.MAX_VALUE;
        double minTcpaSec = 0.0;

        for (CvPredictionResult.PredictedPoint point : trajectory) {
            double t = point.getOffsetSeconds();
            double[] ownExtrapolated = GeoUtils.displace(ownLatitude, ownLongitude, ownVelocityX * t, ownVelocityY * t);
            double distanceMeters = GeoUtils.distanceMetersByXY(
                    ownExtrapolated[0], ownExtrapolated[1],
                    point.getLatitude(), point.getLongitude()
            );

            if (distanceMeters < minDistanceMeters) {
                minDistanceMeters = distanceMeters;
                minTcpaSec = t;
            }
        }

        return new double[]{minDistanceMeters, minTcpaSec};
    }
}
