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

    @Test
    void bearingSectorLabelUsesExpectedBucketsAtBoundaries() {
        assertThat(GeoUtils.bearingSectorLabel(22.4)).isEqualTo("正前方");
        assertThat(GeoUtils.bearingSectorLabel(22.5)).isEqualTo("右舷前方");
        assertThat(GeoUtils.bearingSectorLabel(67.5)).isEqualTo("右舷正横");
        assertThat(GeoUtils.bearingSectorLabel(112.5)).isEqualTo("右舷后方");
        assertThat(GeoUtils.bearingSectorLabel(157.5)).isEqualTo("正后方");
        assertThat(GeoUtils.bearingSectorLabel(202.5)).isEqualTo("左舷后方");
        assertThat(GeoUtils.bearingSectorLabel(247.5)).isEqualTo("左舷正横");
        assertThat(GeoUtils.bearingSectorLabel(292.5)).isEqualTo("左舷前方");
        assertThat(GeoUtils.bearingSectorLabel(337.5)).isEqualTo("正前方");
    }

    private org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
