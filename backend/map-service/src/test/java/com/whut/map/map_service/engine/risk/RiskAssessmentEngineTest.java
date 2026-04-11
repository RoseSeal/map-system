package com.whut.map.map_service.engine.risk;

import com.whut.map.map_service.config.properties.RiskAssessmentProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.engine.collision.CpaTcpaResult;
import com.whut.map.map_service.util.GeoUtils;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskAssessmentEngineTest {

    @Test
    void classifyRiskUsesDefaultThresholds() {
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.45, 600.0)),
                null,
                null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.WARNING);
    }

    @Test
    void classifyRiskUsesConfiguredThresholds() {
        RiskAssessmentProperties properties = new RiskAssessmentProperties();
        properties.setAlarmDcpaNm(0.5);
        properties.setAlarmTcpaSec(700.0);
        RiskAssessmentEngine engine = new RiskAssessmentEngine(properties);
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.45, 600.0)),
                null,
                null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.ALARM);
    }

    private ShipStatus ship(String id) {
        return ShipStatus.builder()
                .id(id)
                .longitude(120.0)
                .latitude(30.0)
                .sog(10.0)
                .cog(90.0)
                .build();
    }

    @Test
    void tcpaZeroWithSmallDcpa_shouldAlarm() {
        // TCPA == 0: ship is exactly at CPA right now. Must not drop to SAFE.
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.25, 0.0)),
                null, null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.ALARM);
    }

    @Test
    void tcpaWithinEpsNegative_shouldStillAlarm() {
        // TCPA = -0.5s: just past CPA but within eps boundary. Should still be ALARM.
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.25, -0.5)),
                null, null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.ALARM);
    }

    @Test
    void tcpaClearlyNegative_shouldBeSafe() {
        // TCPA = -10s: clearly past CPA, ships diverging. DCPA is within alarm range but ships
        // are no longer converging, so risk must be SAFE.
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), cpaTcpa(0.25, -10.0)),
                null, null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.SAFE);
    }

    @Test
    void nullCpaResult_shouldBeSafe() {
        // No CPA data available yet. Must not classify as ALARM due to 0/0 defaults.
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(),
                null, null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.SAFE);
    }

    @Test
    void parallelShipsWithCpaValidFalse_shouldBeSafe() {
        // vel2 < MIN_RELATIVE_SPEED_MS: ships moving in parallel, CPA calculation undefined.
        // Even though cpaDistance is within the alarm threshold, no convergence => SAFE.
        RiskAssessmentEngine engine = new RiskAssessmentEngine(new RiskAssessmentProperties());
        ShipStatus own = ship("own");
        ShipStatus target = ship("target-1");

        CpaTcpaResult parallelResult = CpaTcpaResult.builder()
                .targetMmsi("target-1")
                .cpaDistance(GeoUtils.nmToMeters(0.25)) // within ALARM threshold
                .tcpaTime(0)
                .isApproaching(false)
                .cpaValid(false) // sentinel: relative speed near zero
                .build();

        RiskAssessmentResult result = engine.consume(
                own,
                List.of(own, target),
                Map.of(target.getId(), parallelResult),
                null, null
        );

        assertThat(result.getTargetAssessment(target.getId()).getRiskLevel()).isEqualTo(RiskConstants.SAFE);
    }

    private CpaTcpaResult cpaTcpa(double dcpaNm, double tcpaSec) {
        return CpaTcpaResult.builder()
                .targetMmsi("target-1")
                .cpaDistance(GeoUtils.nmToMeters(dcpaNm))
                .tcpaTime(tcpaSec)
                .isApproaching(tcpaSec > 0)
                .cpaValid(true)
                .build();
    }
}

