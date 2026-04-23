package com.whut.map.map_service.risk.engine.collision;

import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.shared.domain.ShipStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PredictedCpaTcpaBatchCalculator {

    private final PredictedCpaTcpaCalculator predictedCpaTcpaCalculator;

    public Map<String, PredictedCpaTcpaResult> calculateAll(
            ShipStatus ownShip,
            Map<String, CvPredictionResult> predictions
    ) {
        if (ownShip == null || predictions == null || predictions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, PredictedCpaTcpaResult> results = new LinkedHashMap<>();
        for (Map.Entry<String, CvPredictionResult> entry : predictions.entrySet()) {
            PredictedCpaTcpaResult result = predictedCpaTcpaCalculator.calculate(ownShip, entry.getValue());
            if (result != null) {
                results.put(entry.getKey(), result);
            }
        }
        return results;
    }

    public PredictedCpaTcpaResult calculateOne(ShipStatus ownShip, CvPredictionResult prediction) {
        if (ownShip == null || prediction == null) {
            return null;
        }
        return predictedCpaTcpaCalculator.calculate(ownShip, prediction);
    }
}
