package com.whut.map.map_service.navigation.route;

import com.whut.map.map_service.navigation.NavigationTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannedRouteRepositoryTest {

    @Test
    void loadsCommittedDemoRoute() {
        PlannedRoute route = NavigationTestSupport.routeRepository(NavigationTestSupport.productionProperties()).current();

        assertThat(route.routeId()).isEqualTo("wuhan-demo-own");
        assertThat(route.waypoints()).hasSize(4);
        assertThat(route.legs()).hasSize(3);
    }

    @Test
    void rejectsLegsThatDoNotConnectConsecutiveWaypoints() {
        PlannedRoute route = new PlannedRoute("r", "r", true,
                List.of(new RouteWaypoint(1, 114.3, 30.58, 0), new RouteWaypoint(2, 114.3, 30.59, 0)),
                List.of());

        assertThatThrownBy(() -> PlannedRouteRepository.validate(route))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legs");
    }

    @Test
    void rejectsWaypointOutsideBoundingBoxAndNonPositiveXtd() {
        PlannedRoute outside = new PlannedRoute("r", "r", true,
                List.of(new RouteWaypoint(1, 120.0, 30.58, 0), new RouteWaypoint(2, 114.3, 30.59, 0)),
                List.of(new RouteLeg(1, 2, 10, 150, 150)));
        PlannedRoute zeroXtd = new PlannedRoute("r", "r", true,
                List.of(new RouteWaypoint(1, 114.3, 30.58, 0), new RouteWaypoint(2, 114.3, 30.59, 0)),
                List.of(new RouteLeg(1, 2, 10, 0, 150)));

        assertThatThrownBy(() -> PlannedRouteRepository.validate(outside)).hasMessageContaining("bounding box");
        assertThatThrownBy(() -> PlannedRouteRepository.validate(zeroXtd)).hasMessageContaining("xtd");
    }
}
