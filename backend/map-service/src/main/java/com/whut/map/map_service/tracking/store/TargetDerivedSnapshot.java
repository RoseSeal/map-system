package com.whut.map.map_service.tracking.store;

import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;

public record TargetDerivedSnapshot(
    String targetId,
    CvPredictionResult predictionResult,
    CpaTcpaResult cpaResult,
    PredictedCpaTcpaResult predictedCpaResult,
    EncounterClassificationResult encounterResult,
    TargetRiskAssessment riskAssessment
) {}
