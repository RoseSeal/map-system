package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.collision.PredictedCpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TargetStateSnapshotDeepCopier {

    public Map<String, TargetDerivedSnapshot> copyAll(Map<String, TargetDerivedSnapshot> source) {
        if (source == null) return Collections.emptyMap();
        Map<String, TargetDerivedSnapshot> result = new HashMap<>();
        source.forEach((id, snapshot) -> result.put(id, copySnapshot(snapshot)));
        return Collections.unmodifiableMap(result);
    }

    private TargetDerivedSnapshot copySnapshot(TargetDerivedSnapshot source) {
        if (source == null) return null;
        return new TargetDerivedSnapshot(
                source.targetId(),
                copyCvPrediction(source.predictionResult()),
                copyCpaTcpa(source.cpaResult()),
                copyPredictedCpaTcpa(source.predictedCpaResult()),
                copyEncounterClassification(source.encounterResult()),
                copyRiskAssessment(source.riskAssessment())
        );
    }

    private CvPredictionResult copyCvPrediction(CvPredictionResult source) {
        if (source == null) return null;
        List<CvPredictionResult.PredictedPoint> trajectory = source.getTrajectory() == null ? null :
                source.getTrajectory().stream()
                        .map(p -> CvPredictionResult.PredictedPoint.builder()
                                .latitude(p.getLatitude())
                                .longitude(p.getLongitude())
                                .offsetSeconds(p.getOffsetSeconds())
                                .build())
                        .toList();
        return CvPredictionResult.builder()
                .targetId(source.getTargetId())
                .trajectory(trajectory)
                .predictionTime(source.getPredictionTime())
                .horizonSeconds(source.getHorizonSeconds())
                .build();
    }

    private CpaTcpaResult copyCpaTcpa(CpaTcpaResult source) {
        if (source == null) return null;
        return CpaTcpaResult.builder()
                .targetMmsi(source.getTargetMmsi())
                .cpaDistance(source.getCpaDistance())
                .tcpaTime(source.getTcpaTime())
                .isApproaching(source.isApproaching())
                .cpaValid(source.isCpaValid())
                .build();
    }

    private PredictedCpaTcpaResult copyPredictedCpaTcpa(PredictedCpaTcpaResult source) {
        if (source == null) return null;
        return PredictedCpaTcpaResult.builder()
                .targetMmsi(source.getTargetMmsi())
                .cpaDistanceMeters(source.getCpaDistanceMeters())
                .tcpaSeconds(source.getTcpaSeconds())
                .approaching(source.isApproaching())
                .ownCpaLatitude(source.getOwnCpaLatitude())
                .ownCpaLongitude(source.getOwnCpaLongitude())
                .targetCpaLatitude(source.getTargetCpaLatitude())
                .targetCpaLongitude(source.getTargetCpaLongitude())
                .build();
    }

    private EncounterClassificationResult copyEncounterClassification(EncounterClassificationResult source) {
        if (source == null) return null;
        return EncounterClassificationResult.builder()
                .targetId(source.getTargetId())
                .encounterType(source.getEncounterType())
                .relativeBearingDeg(source.getRelativeBearingDeg())
                .courseDifferenceDeg(source.getCourseDifferenceDeg())
                .build();
    }

    private TargetRiskAssessment copyRiskAssessment(TargetRiskAssessment source) {
        if (source == null) return null;
        return TargetRiskAssessment.builder()
                .targetId(source.getTargetId())
                .riskLevel(source.getRiskLevel())
                .cpaDistanceMeters(source.getCpaDistanceMeters())
                .tcpaSeconds(source.getTcpaSeconds())
                .approaching(source.isApproaching())
                .explanationSource(source.getExplanationSource())
                .explanationText(source.getExplanationText())
                .riskScore(source.getRiskScore())
                .riskConfidence(source.getRiskConfidence())
                .encounterType(source.getEncounterType())
                .domainPenetration(source.getDomainPenetration())
                .build();
    }
}
