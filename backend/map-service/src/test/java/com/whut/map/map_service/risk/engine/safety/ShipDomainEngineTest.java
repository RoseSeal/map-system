package com.whut.map.map_service.risk.engine.safety;

import com.whut.map.map_service.risk.config.ShipDomainProperties;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ShipDomainEngineTest {

    private final ShipDomainProperties properties = new ShipDomainProperties();
    private final ShipDomainEngine engine = new ShipDomainEngine(properties);

    @Test
    void returnsBaselineDomainAtReferenceSpeed() {
        ShipDomainResult result = engine.consume(shipWithSog(8.0));

        assertThat(result.getForeNm()).isEqualTo(0.2);
        assertThat(result.getAftNm()).isEqualTo(0.04);
        assertThat(result.getPortNm()).isEqualTo(0.08);
        assertThat(result.getStbdNm()).isEqualTo(0.08);
        assertThat(result.getShapeType()).isEqualTo(ShipDomainResult.SHAPE_ELLIPSE);
    }

    @Test
    void clampsLowSpeedToMinimumFactor() {
        ShipDomainResult result = engine.consume(shipWithSog(0.0));

        assertThat(result.getForeNm()).isEqualTo(0.1);
        assertThat(result.getAftNm()).isEqualTo(0.02);
        assertThat(result.getPortNm()).isEqualTo(0.04);
        assertThat(result.getStbdNm()).isEqualTo(0.04);
    }

    @Test
    void clampsHighSpeedToMaximumFactor() {
        ShipDomainResult result = engine.consume(shipWithSog(20.0));

        assertThat(result.getForeNm()).isEqualTo(0.4);
        assertThat(result.getAftNm()).isEqualTo(0.08);
        assertThat(result.getPortNm()).isEqualTo(0.16);
        assertThat(result.getStbdNm()).isEqualTo(0.16);
    }

    @Test
    void scalesLinearlyWithinClampRange() {
        ShipDomainResult result = engine.consume(shipWithSog(12.0));

        assertThat(result.getForeNm()).isCloseTo(0.3, within(1e-12));
        assertThat(result.getAftNm()).isCloseTo(0.06, within(1e-12));
        assertThat(result.getPortNm()).isCloseTo(0.12, within(1e-12));
        assertThat(result.getStbdNm()).isCloseTo(0.12, within(1e-12));
    }

    @Test
    void fallsBackToReferenceSpeedForInvalidSog() {
        assertBaseline(engine.consume(shipWithSog(102.3)));
        assertBaseline(engine.consume(shipWithSog(200.0)));
        assertBaseline(engine.consume(shipWithSog(-1.0)));
        assertBaseline(engine.consume(shipWithSog(Double.NaN)));
    }

    @Test
    void fallsBackToBaselineWhenReferenceSpeedIsInvalid() {
        ShipDomainProperties invalidProperties = new ShipDomainProperties();
        invalidProperties.setReferenceSpeedKn(0.0);
        ShipDomainEngine invalidEngine = new ShipDomainEngine(invalidProperties);

        ShipDomainResult result = invalidEngine.consume(shipWithSog(8.0));

        assertBaseline(result);
    }

    private void assertBaseline(ShipDomainResult result) {
        assertThat(result.getForeNm()).isEqualTo(0.2);
        assertThat(result.getAftNm()).isEqualTo(0.04);
        assertThat(result.getPortNm()).isEqualTo(0.08);
        assertThat(result.getStbdNm()).isEqualTo(0.08);
        assertThat(result.getShapeType()).isEqualTo(ShipDomainResult.SHAPE_ELLIPSE);
    }

    private ShipStatus shipWithSog(double sog) {
        return ShipStatus.builder()
                .id("ownShip")
                .role(ShipRole.OWN_SHIP)
                .sog(sog)
                .build();
    }
}
