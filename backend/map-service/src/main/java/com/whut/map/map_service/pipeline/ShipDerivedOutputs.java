package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;

import java.util.Map;

record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        CvPredictionResult cvPredictionResult,
        Map<String, CpaTcpaResult> cpaResults
) {
}
