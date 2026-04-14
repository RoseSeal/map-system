package com.whut.map.map_service.store;

import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;

public record TargetDerivedSnapshot(
    String targetId,
    CvPredictionResult predictionResult,
    CpaTcpaResult cpaResult,
    EncounterClassificationResult encounterResult,
    TargetRiskAssessment riskAssessment
) {}
