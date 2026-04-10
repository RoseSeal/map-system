package com.whut.map.map_service.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsBearingTest {

    @Test
    void trueBearingPointsNorthAtZeroDegrees() {
        assertThat(GeoUtils.trueBearing(30.0, 120.0, 30.01, 120.0)).isCloseTo(0.0, within(1.0));
    }

    @Test
    void trueBearingPointsEastAtNinetyDegrees() {
        assertThat(GeoUtils.trueBearing(30.0, 120.0, 30.0, 120.01)).isCloseTo(90.0, within(1.0));
    }

    @Test
    void trueBearingPointsSouthwestInThirdQuadrant() {
        assertThat(GeoUtils.trueBearing(30.0, 120.0, 29.99, 119.99)).isCloseTo(225.0, within(5.0));
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
