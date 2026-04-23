package com.whut.map.map_service.risk.pipeline;

import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;

import java.util.Map;

record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        Map<String, CvPredictionResult> cvPredictionResults,
        Map<String, CpaTcpaResult> cpaResults,
        Map<String, PredictedCpaTcpaResult> predictedCpaResults,
        Map<String, EncounterClassificationResult> encounterResults
) {
}
