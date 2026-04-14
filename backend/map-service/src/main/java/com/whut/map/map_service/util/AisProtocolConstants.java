package com.whut.map.map_service.util;

public final class AisProtocolConstants {

    private AisProtocolConstants() {
    }

    /**
     * AIS SOG not-available sentinel value (ITU-R M.1371), unit: knots.
     */
    public static final double SOG_NOT_AVAILABLE_KN = 102.3;

    /**
     * AIS COG not-available sentinel value (ITU-R M.1371), unit: degrees.
     */
    public static final double COG_NOT_AVAILABLE_DEG = 360.0;

    /**
     * Valid COG range: [0, 360), excluding NaN.
     */
    public static boolean isValidCog(double cog) {
        return !Double.isNaN(cog) && cog >= 0.0 && cog < COG_NOT_AVAILABLE_DEG;
    }

    /**
     * Valid SOG range: [0, 102.3), excluding NaN.
     */
    public static boolean isValidSog(double sog) {
        return !Double.isNaN(sog) && sog >= 0.0 && sog < SOG_NOT_AVAILABLE_KN;
    }
}