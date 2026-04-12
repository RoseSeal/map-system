package com.whut.map.map_service.pipeline.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.engine.encounter.EncounterType;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TargetAssemblerTest {

    @Test
    @SuppressWarnings("unchecked")
    void assembleTargetWithCvPrediction() {
        RiskVisualizationAssembler riskVisualizationAssembler = new RiskVisualizationAssembler();
        TargetAssembler assembler = new TargetAssembler(riskVisualizationAssembler);

        ShipStatus ownShip = ShipStatus.builder().id("own").build();
        ShipStatus targetShip = ShipStatus.builder().id("target-1").sog(10.0).cog(45.0).longitude(120.0).latitude(30.0).build();

        CvPredictionResult cvResult = CvPredictionResult.builder()
                .targetId("target-1")
                .horizonSeconds(600)
                .trajectory(List.of(
                        CvPredictionResult.PredictedPoint.builder().latitude(30.01).longitude(120.01).offsetSeconds(30).build(),
                        CvPredictionResult.PredictedPoint.builder().latitude(30.02).longitude(120.02).offsetSeconds(60).build()
                ))
                .build();

        Map<String, Object> targetMap = assembler.assembleTarget(ownShip, targetShip, null, null, cvResult, null);

        assertThat(targetMap).containsKey("predicted_trajectory");
        Map<String, Object> predictedTrajectory = (Map<String, Object>) targetMap.get("predicted_trajectory");
        assertThat(predictedTrajectory).containsEntry("prediction_type", "cv");
        assertThat(predictedTrajectory).containsEntry("horizon_seconds", 600);

        List<Map<String, Object>> points = (List<Map<String, Object>>) predictedTrajectory.get("points");
        assertThat(points).hasSize(2);
        assertThat(points.get(0)).containsEntry("offset_seconds", 30);
    }

    @Test
    void assembleTargetWithoutCvPrediction() {
        RiskVisualizationAssembler riskVisualizationAssembler = new RiskVisualizationAssembler();
        TargetAssembler assembler = new TargetAssembler(riskVisualizationAssembler);

        ShipStatus ownShip = ShipStatus.builder().id("own").build();
        ShipStatus targetShip = ShipStatus.builder().id("target-1").sog(10.0).cog(45.0).longitude(120.0).latitude(30.0).build();

        Map<String, Object> targetMap = assembler.assembleTarget(ownShip, targetShip, null, null, null, null);

        assertThat(targetMap).doesNotContainKey("predicted_trajectory");
    }

    @Test
    void assembleTargetWithEmptyTrajectory() {
        RiskVisualizationAssembler riskVisualizationAssembler = new RiskVisualizationAssembler();
        TargetAssembler assembler = new TargetAssembler(riskVisualizationAssembler);

        ShipStatus ownShip = ShipStatus.builder().id("own").build();
        ShipStatus targetShip = ShipStatus.builder().id("target-1").sog(10.0).cog(45.0).longitude(120.0).latitude(30.0).build();

        CvPredictionResult cvResult = CvPredictionResult.builder()
                .targetId("target-1")
                .horizonSeconds(600)
                .trajectory(List.of())
                .build();

        Map<String, Object> targetMap = assembler.assembleTarget(ownShip, targetShip, null, null, cvResult, null);

        assertThat(targetMap).doesNotContainKey("predicted_trajectory");
    }

    @Test
    @SuppressWarnings("unchecked")
    void assembleTargetWithEncounterType() {
        RiskVisualizationAssembler riskVisualizationAssembler = new RiskVisualizationAssembler();
        TargetAssembler assembler = new TargetAssembler(riskVisualizationAssembler);

        ShipStatus ownShip = ShipStatus.builder().id("own").build();
        ShipStatus targetShip = ShipStatus.builder().id("target-1").sog(10.0).cog(45.0).longitude(120.0).latitude(30.0).build();

        EncounterClassificationResult encounterResult = EncounterClassificationResult.builder()
                .targetId("target-1")
                .encounterType(EncounterType.CROSSING)
                .build();

        Map<String, Object> targetMap = assembler.assembleTarget(ownShip, targetShip, null, null, null, encounterResult);

        assertThat(targetMap).containsKey("risk_assessment");
        Map<String, Object> riskAssessment = (Map<String, Object>) targetMap.get("risk_assessment");
        assertThat(riskAssessment).containsEntry("encounter_type", "CROSSING");
    }
}
