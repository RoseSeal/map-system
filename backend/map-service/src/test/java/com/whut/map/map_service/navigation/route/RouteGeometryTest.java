package com.whut.map.map_service.navigation.route;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteGeometryTest {

    private static final double METERS_PER_DEGREE = 111320.0;

    private static PlannedRoute route(double[]... points) {
        List<RouteWaypoint> waypoints = new java.util.ArrayList<>();
        List<RouteLeg> legs = new java.util.ArrayList<>();
        for (int i = 0; i < points.length; i++) {
            waypoints.add(new RouteWaypoint(i + 1, points[i][0], points[i][1], 0));
            if (i > 0) {
                legs.add(new RouteLeg(i, i + 1, 10, 150, 150));
            }
        }
        return new PlannedRoute("r", "r", true, waypoints, legs);
    }

    private static double eastDegrees(double meters, double lat) {
        return meters / (Math.cos(Math.toRadians(lat)) * METERS_PER_DEGREE);
    }

    @Test
    void straightRouteGivesAlongDistanceAndStarboardNegativeOffset() {
        RouteGeometry geometry = RouteGeometry.of(route(new double[]{114.3, 30.58}, new double[]{114.3, 30.60}));

        RouteProjection starboard = geometry.project(114.3 + eastDegrees(100, 30.58), 30.59);
        RouteProjection port = geometry.project(114.3 - eastDegrees(100, 30.58), 30.59);

        assertThat(geometry.totalLengthMeters()).isCloseTo(0.02 * METERS_PER_DEGREE, within(0.01));
        assertThat(starboard.alongMeters()).isCloseTo(0.01 * METERS_PER_DEGREE, within(0.01));
        assertThat(starboard.signedOffsetMeters()).isCloseTo(-100.0, within(0.01));
        assertThat(port.signedOffsetMeters()).isCloseTo(100.0, within(0.01));
        assertThat(starboard.distanceMeters()).isCloseTo(100.0, within(0.01));
    }

    @Test
    void polylineProjectsOntoNearestLegWithCumulativeDistance() {
        // north 0.01 deg, then east 0.01 deg (reference latitude 30.58)
        RouteGeometry geometry = RouteGeometry.of(route(
                new double[]{114.3, 30.58}, new double[]{114.3, 30.59}, new double[]{114.31, 30.59}));
        double firstLeg = 0.01 * METERS_PER_DEGREE;
        double secondLeg = 0.01 * Math.cos(Math.toRadians(30.58)) * METERS_PER_DEGREE;

        RouteProjection onSecond = geometry.project(114.305, 30.5891);

        assertThat(geometry.totalLengthMeters()).isCloseTo(firstLeg + secondLeg, within(0.01));
        assertThat(onSecond.legIndex()).isEqualTo(1);
        assertThat(onSecond.alongMeters()).isCloseTo(firstLeg + secondLeg / 2, within(0.5));
        // south of an eastbound leg is starboard
        assertThat(onSecond.signedOffsetMeters()).isCloseTo(-0.0009 * METERS_PER_DEGREE, within(0.01));
    }

    @Test
    void pointBeforeStartClampsToStartWithTrueDistance() {
        RouteGeometry geometry = RouteGeometry.of(route(new double[]{114.3, 30.58}, new double[]{114.3, 30.60}));

        RouteProjection behind = geometry.project(114.3, 30.579);

        assertThat(behind.alongMeters()).isZero();
        assertThat(behind.distanceMeters()).isCloseTo(0.001 * METERS_PER_DEGREE, within(0.01));
    }
}
