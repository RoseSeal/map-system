package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;

import java.util.Map;

record ShipDerivedOutputs(
        ShipDomainResult shipDomainResult,
        Map<String, CvPredictionResult> cvPredictionResults,
        Map<String, CpaTcpaResult> cpaResults,
        Map<String, EncounterClassificationResult> encounterResults
) {
}
