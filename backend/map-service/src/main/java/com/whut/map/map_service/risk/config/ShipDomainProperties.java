package com.whut.map.map_service.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "engine.ship-domain")
public class ShipDomainProperties {

    /**
     * Baseline forward radius in nautical miles, scaled by sog/referenceSpeedKn.
     */
    private double baseForeNm = 0.5;

    /**
     * Baseline aft radius in nautical miles, scaled by sog/referenceSpeedKn.
     */
    private double baseAftNm = 0.1;

    /**
     * Baseline port-side radius in nautical miles, scaled by sog/referenceSpeedKn.
     */
    private double basePortNm = 0.2;

    /**
     * Baseline starboard-side radius in nautical miles, scaled by sog/referenceSpeedKn.
     */
    private double baseStbdNm = 0.2;

    /**
     * Reference vessel speed in knots used as the divisor for dynamic scaling.
     */
    private double referenceSpeedKn = 8.0;

    /**
     * Lower bound for sog/referenceSpeedKn after scaling.
     */
    private double minSpeedFactor = 0.5;

    /**
     * Upper bound for sog/referenceSpeedKn after scaling.
     */
    private double maxSpeedFactor = 2.0;
}
