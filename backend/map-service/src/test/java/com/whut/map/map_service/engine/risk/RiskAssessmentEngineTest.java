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

    private CpaTcpaResult cpaTcpa(double dcpaNm, double tcpaSec) {
        return CpaTcpaResult.builder()
                .targetMmsi("target-1")
                .cpaDistance(GeoUtils.nmToMeters(dcpaNm))
                .tcpaTime(tcpaSec)
                .isApproaching(tcpaSec > 0)
                .build();
    }
}

