package com.whut.map.map_service.engine.trajectoryprediction;

import com.whut.map.map_service.config.properties.TrajectoryPredictionProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CvPredictionEngine {

    private static final double AIS_SOG_NOT_AVAILABLE_KN = 102.3;
    private final TrajectoryPredictionProperties props;

    private CvPredictionResult predict(ShipStatus ship) {
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
        double[] velocity = GeoUtils.toVelocity(sog, safeCog);
        int step = props.getStepSeconds();
        int horizon = props.getHorizonSeconds();

        List<CvPredictionResult.PredictedPoint> points = new ArrayList<>();
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

        return CvPredictionResult.builder()
                .targetId(ship.getId())
                .trajectory(points)
                .predictionTime(predictionTime)
                .horizonSeconds(horizon)
                .build();
    }

    public CvPredictionResult consume(ShipStatus message) {
        log.debug("CV prediction for target MMSI: {}", message.getId());
        return predict(message);
    }
}
