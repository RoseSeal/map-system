package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaCalculator;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PredictedCpaCalculator {

    private final PredictedCpaTcpaCalculator predictedCpaTcpaCalculator;

    /**
     * @return {predictedCpaNm, predictedTcpaSec}，或 null 如果无法计算
     */
    public double[] calculate(ShipStatus ownShip, CvPredictionResult targetPrediction) {
        PredictedCpaTcpaResult result = predictedCpaTcpaCalculator.calculate(ownShip, targetPrediction);
        if (result == null) {
            return null;
        }

        return new double[]{
                GeoUtils.metersToNm(result.getCpaDistanceMeters()),
                result.getTcpaSeconds()
        };
    }
}
