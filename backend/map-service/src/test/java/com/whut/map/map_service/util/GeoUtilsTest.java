package com.whut.map.map_service.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoUtilsTest {

    @Test
    void convertsMetersToNmAndBack() {
        assertThat(GeoUtils.metersToNm(1852.0)).isEqualTo(1.0);
        assertThat(GeoUtils.nmToMeters(1.0)).isEqualTo(1852.0);
    }

    @Test
    void distanceByXyIsZeroForSamePoint() {
        assertThat(GeoUtils.distanceMetersByXY(30.0, 120.0, 30.0, 120.0)).isZero();
    }

    @Test
    void distanceByXyUsesProjectedEuclideanDistance() {
        double[] p1 = GeoUtils.toXY(30.0, 120.0);
        double[] p2 = GeoUtils.toXY(30.01, 120.02);
        double expected = Math.sqrt(Math.pow(p2[0] - p1[0], 2) + Math.pow(p2[1] - p1[1], 2));

        assertThat(GeoUtils.distanceMetersByXY(30.0, 120.0, 30.01, 120.02)).isEqualTo(expected);
    }

    @Test
    void displaceZeroReturnsSame() {
        double[] res = GeoUtils.displace(30.0, 120.0, 0, 0);
        assertThat(res[0]).isEqualTo(30.0);
        assertThat(res[1]).isEqualTo(120.0);
    }

    @Test
    void displaceEastIncreasesLongitude() {
        double[] res = GeoUtils.displace(30.0, 120.0, 1000, 0);
        assertThat(res[0]).isEqualTo(30.0);
        assertThat(res[1]).isGreaterThan(120.0);
    }

    @Test
    void displaceNorthIncreasesLatitude() {
        double[] res = GeoUtils.displace(30.0, 120.0, 0, 1000);
        assertThat(res[0]).isGreaterThan(30.0);
        assertThat(res[1]).isEqualTo(120.0);
    }

    @Test
    void testAngleDifference() {
        assertThat(GeoUtils.angleDifference(10, 350)).isEqualTo(20.0);
        assertThat(GeoUtils.angleDifference(0, 180)).isEqualTo(180.0);
        assertThat(GeoUtils.angleDifference(90, 90)).isEqualTo(0.0);
        assertThat(GeoUtils.angleDifference(0, 270)).isEqualTo(90.0);
        assertThat(GeoUtils.angleDifference(350, 10)).isEqualTo(20.0);
    }
}

