package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.config.properties.RiskAssessmentProperties;
import com.whut.map.map_service.config.properties.RiskScoringProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.engine.encounter.EncounterClassificationResult;
import com.whut.map.map_service.engine.encounter.EncounterType;
import com.whut.map.map_service.engine.safety.ShipDomainResult;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionResult;
import com.whut.map.map_service.util.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessmentEngineTest {

    private RiskAssessmentEngine createEngine(RiskAssessmentProperties properties) {
        return new RiskAssessmentEngine(
                properties,
                new RiskScoringProperties(),
                new DomainPenetrationCalculator(),
                new PredictedCpaCalculator()
        );
    }

    @Test
    void classifyRiskUsesDefaultThresholds() {
        RiskAssessmentEngine engine = createEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.45, 600.0)),
                null,
                null,
                null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.WARNING);
    }

    @Test
    void riskScoreShouldIncreaseWithDomainPenetration() {
        RiskAssessmentEngine engine = createEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");
        
        // Scenario 1: No domain data
        TargetRiskAssessment lowScore = engine.consume(own, List.of(target), Map.of(target.getId(), cpaTcpa(0.8, 1000)), null, null, null)
                .getTargetAssessment(target.getId());

        // Scenario 2: Domain penetration (inside 0.5nm domain at 0.4nm)
        ShipDomainResult domain = ShipDomainResult.builder().foreNm(1.0).aftNm(1.0).portNm(1.0).stbdNm(1.0).build();
        TargetRiskAssessment highScore = engine.consume(own, List.of(target), Map.of(target.getId(), cpaTcpa(0.4, 1000)), domain, null, null)
                .getTargetAssessment(target.getId());

        assertThat(highScore.getRiskScore()).isGreaterThan(lowScore.getRiskScore());
        assertThat(highScore.getDomainPenetration()).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void riskScoreShouldBeModifiedByEncounterType() {
        RiskScoringProperties scoring = new RiskScoringProperties();
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties(), scoring, new DomainPenetrationCalculator(), new PredictedCpaCalculator());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");
        CpaTcpaResult cpa = cpaTcpa(0.5, 600);

        // HEAD_ON (1.2x)
        EncounterClassificationResult headOn = EncounterClassificationResult.builder().encounterType(EncounterType.HEAD_ON).build();
        double headOnScore = engine.consume(own, List.of(target), Map.of(target.getId(), cpa), null, null, Map.of(target.getId(), headOn))
                .getTargetAssessment(target.getId()).getRiskScore();

        // OVERTAKING (0.8x)
        EncounterClassificationResult overtaking = EncounterClassificationResult.builder().encounterType(EncounterType.OVERTAKING).build();
        double overtakingScore = engine.consume(own, List.of(target), Map.of(target.getId(), cpa), null, null, Map.of(target.getId(), overtaking))
                .getTargetAssessment(target.getId()).getRiskScore();

        assertThat(headOnScore).isGreaterThan(overtakingScore);
    }

    @Test
    void riskScoreShouldReactToPredictionCorrection() {
        RiskAssessmentProperties properties = new RiskAssessmentProperties();
        properties.setCautionDcpaNm(1.0); 
        RiskAssessmentEngine engine = createEngine(properties);
        
        ShipStatus own = ship("own");
        // own is at (30, 120) moving 90deg (East)
        ShipStatus target = ship("target-1");
        
        CpaTcpaResult currentCpa = cpaTcpa(0.8, 0); 

        // 1. Base Score
        double baseScore = engine.consume(own, List.of(target), Map.of(target.getId(), currentCpa), null, null, null)
                .getTargetAssessment(target.getId()).getRiskScore();

        // 2. Worsening: target predicted point is at distance 0.1nm
        double distMeters = GeoUtils.nmToMeters(0.1);
        double[] worsePos = GeoUtils.displace(own.getLatitude(), own.getLongitude(), 0, distMeters); // North of own
        
        CvPredictionResult worsening = CvPredictionResult.builder().trajectory(List.of(
                CvPredictionResult.PredictedPoint.builder().latitude(worsePos[0]).longitude(worsePos[1]).offsetSeconds(0).build()
        )).build();
        
        double worseningScore = engine.consume(own, List.of(target), Map.of(target.getId(), currentCpa), null, Map.of(target.getId(), worsening), null)
                .getTargetAssessment(target.getId()).getRiskScore();

        assertThat(worseningScore).as("Worsening score (" + worseningScore + ") vs base (" + baseScore + ")")
                .isGreaterThan(baseScore);
    }

    private ShipStatus ship(String id) {
        return ShipStatus.builder()
                .id(id)
                .longitude(120.0).latitude(30.0)
                .sog(10.0).cog(90.0).heading(90.0).confidence(1.0)
                .build();
    }

    private ShipStatus ship(double lat, double lon, double cog) {
        return ShipStatus.builder()
                .latitude(lat).longitude(lon)
                .sog(10.0).cog(cog).heading(cog).confidence(1.0)
                .build();
    }

    private CpaTcpaResult cpaTcpa(double dcpaNm, double tcpaSec) {
        return CpaTcpaResult.builder()
                .targetMmsi("target-1")
                .cpaDistance(GeoUtils.nmToMeters(dcpaNm))
                .tcpaTime(tcpaSec)
                .isApproaching(tcpaSec > -1.0)
                .cpaValid(true)
                .build();
    }

    @Test
    void parallelShipsWithCpaValidFalse_shouldBeSafe() {
        // cpaValid=false: relative motion too small to determine convergence.
        // Even inside ALARM distance threshold, no approaching trend → riskLevel must be SAFE.
        RiskAssessmentEngine engine = createEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        CpaTcpaResult parallelResult = CpaTcpaResult.builder()
                .targetMmsi("target-1")
                .cpaDistance(GeoUtils.nmToMeters(0.25)) // within ALARM threshold
                .tcpaTime(0)
                .isApproaching(false)
                .cpaValid(false)
                .build();

        RiskAssessmentResult result = engine.consume(own, List.of(own, target), Map.of(target.getId(), parallelResult), null, null, null);
        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.SAFE);
    }
}
