package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.risk.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.risk.engine.encounter.EncounterType;
import com.whut.map.map_service.risk.engine.risk.TargetRiskAssessment;
import com.whut.map.map_service.risk.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetStateSnapshotDeepCopierTest {

    private final TargetStateSnapshotDeepCopier copier = new TargetStateSnapshotDeepCopier();

    @Test
    void modifyingCopiedCpaTcpaDoesNotAffectSource() {
        CpaTcpaResult sourceCpa = CpaTcpaResult.builder()
                .targetMmsi("t1")
                .cpaDistance(0.5)
                .tcpaTime(200.0)
                .isApproaching(true)
                .cpaValid(true)
                .build();
        TargetDerivedSnapshot sourceSnapshot = new TargetDerivedSnapshot("t1", null, sourceCpa, null, null);
        Map<String, TargetDerivedSnapshot> source = Map.of("t1", sourceSnapshot);

        Map<String, TargetDerivedSnapshot> copy = copier.copyAll(source);
        copy.get("t1").cpaResult().setCpaDistance(99.0);

        assertThat(sourceCpa.getCpaDistance()).isEqualTo(0.5);
    }

    @Test
    void returnedMapIsUnmodifiable() {
        Map<String, TargetDerivedSnapshot> copy = copier.copyAll(Map.of());

        assertThatThrownBy(() -> copy.put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allInnerFieldsAreCopied() {
        CvPredictionResult prediction = CvPredictionResult.builder()
                .targetId("t1")
                .horizonSeconds(60)
                .trajectory(List.of(
                        CvPredictionResult.PredictedPoint.builder()
                                .latitude(30.0).longitude(120.0).offsetSeconds(10).build()
                ))
                .build();
        CpaTcpaResult cpa = CpaTcpaResult.builder()
                .targetMmsi("t1").cpaDistance(1.0).tcpaTime(300.0)
                .isApproaching(true).cpaValid(true).build();
        EncounterClassificationResult encounter = EncounterClassificationResult.builder()
                .targetId("t1").encounterType(EncounterType.HEAD_ON)
                .relativeBearingDeg(5.0).courseDifferenceDeg(175.0).build();
        TargetRiskAssessment risk = TargetRiskAssessment.builder()
                .targetId("t1").riskLevel("WARNING")
                .cpaDistanceMeters(1852.0).tcpaSeconds(300.0)
                .approaching(true).riskScore(0.8).riskConfidence(0.9).build();

        TargetDerivedSnapshot original = new TargetDerivedSnapshot("t1", prediction, cpa, encounter, risk);
        Map<String, TargetDerivedSnapshot> copy = copier.copyAll(Map.of("t1", original));

        TargetDerivedSnapshot copied = copy.get("t1");
        assertThat(copied).isNotSameAs(original);
        assertThat(copied.cpaResult()).isNotSameAs(cpa);
        assertThat(copied.encounterResult()).isNotSameAs(encounter);
        assertThat(copied.riskAssessment()).isNotSameAs(risk);
        assertThat(copied.predictionResult()).isNotSameAs(prediction);
        assertThat(copied.predictionResult().getTrajectory().get(0))
                .isNotSameAs(prediction.getTrajectory().get(0));

        assertThat(copied.cpaResult().getCpaDistance()).isEqualTo(1.0);
        assertThat(copied.encounterResult().getEncounterType()).isEqualTo(EncounterType.HEAD_ON);
        assertThat(copied.riskAssessment().getRiskScore()).isEqualTo(0.8);
    }

    @Test
    void emptySourceMapReturnsEmptyUnmodifiableMap() {
        Map<String, TargetDerivedSnapshot> copy = copier.copyAll(Map.of());
        assertThat(copy).isEmpty();
        assertThatThrownBy(() -> copy.put("x", null))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
