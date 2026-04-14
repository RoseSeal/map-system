package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.risk.engine.safety.ShipDomainResult;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DomainPenetrationCalculatorTest {

    private final DomainPenetrationCalculator calculator = new DomainPenetrationCalculator();

    @Test
    void shouldReturnPositiveWhenInsideDomain() {
        ShipStatus own = ship(30.0, 120.0, 90.0);
        // target 0.1nm exactly in front
        double distMeters = GeoUtils.nmToMeters(0.1);
        double[] targetPos = GeoUtils.displace(30.0, 120.0, distMeters, 0); // 90deg is east, so dx=dist
        ShipStatus target = ship(targetPos[0], targetPos[1], 90.0);

        ShipDomainResult domain = domain(0.5, 0.2, 0.2, 0.2);
        
        Double penetration = calculator.calculate(own, target, domain);
        assertThat(penetration).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void shouldReturnNegativeWhenOutsideDomain() {
        ShipStatus own = ship(30.0, 120.0, 90.0);
        // target 0.8nm exactly in front, domain fore is 0.5
        double distMeters = GeoUtils.nmToMeters(0.8);
        double[] targetPos = GeoUtils.displace(30.0, 120.0, distMeters, 0);
        ShipStatus target = ship(targetPos[0], targetPos[1], 90.0);

        ShipDomainResult domain = domain(0.5, 0.2, 0.2, 0.2);

        Double penetration = calculator.calculate(own, target, domain);
        assertThat(penetration).isNotNull().isLessThan(0.0);
    }

    @Test
    void shouldHandleHeadingFallbackToCog() {
        ShipStatus own = ShipStatus.builder()
                .latitude(30.0).longitude(120.0)
                .heading(null).cog(90.0) // fallback to 90
                .build();
        double distMeters = GeoUtils.nmToMeters(0.1);
        double[] targetPos = GeoUtils.displace(30.0, 120.0, distMeters, 0);
        ShipStatus target = ship(targetPos[0], targetPos[1], 90.0);

        ShipDomainResult domain = domain(0.5, 0.2, 0.2, 0.2);
        Double penetration = calculator.calculate(own, target, domain);
        assertThat(penetration).isNotNull().isGreaterThan(0.0);
    }

    @Test
    void shouldReturnNullWhenInputsMissing() {
        assertThat(calculator.calculate(null, ship(30, 120, 0), domain(1,1,1,1))).isNull();
        assertThat(calculator.calculate(ship(30,120,0), null, domain(1,1,1,1))).isNull();
        assertThat(calculator.calculate(ship(30,120,0), ship(30,121,0), null)).isNull();
    }

    private ShipStatus ship(double lat, double lon, double cog) {
        return ShipStatus.builder()
                .latitude(lat).longitude(lon)
                .sog(10.0).cog(cog).heading(cog)
                .build();
    }

    private ShipDomainResult domain(double fore, double aft, double port, double stbd) {
        return ShipDomainResult.builder()
                .foreNm(fore).aftNm(aft).portNm(port).stbdNm(stbd)
                .shapeType("ellipse")
                .build();
    }
}
