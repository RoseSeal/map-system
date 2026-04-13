package com.whut.map.map_service.util;

public class MathUtils {
    private MathUtils() {
        // Prevent instantiation
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }
}
