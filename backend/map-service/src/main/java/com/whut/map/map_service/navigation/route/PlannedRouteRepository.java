package com.whut.map.map_service.navigation.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.navigation.config.NavigationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 持有当前生效的本船计划航线；启动时从配置资源加载并校验，校验失败则启动失败。
 */
@Slf4j
@Component
public class PlannedRouteRepository {

    // 武汉航道局辖区近似包围盒，与 notice-pipeline 校验一致；只用于拦截明显错误。
    static final double LON_MIN = 113.0;
    static final double LON_MAX = 116.5;
    static final double LAT_MIN = 29.3;
    static final double LAT_MAX = 31.0;

    private final AtomicReference<PlannedRoute> current = new AtomicReference<>();

    public PlannedRouteRepository(
            NavigationProperties properties,
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper
    ) {
        String location = properties.getRoute().getActiveResource();
        try (InputStream in = resourceLoader.getResource(location).getInputStream()) {
            PlannedRoute route = objectMapper.readValue(in, PlannedRoute.class);
            validate(route);
            current.set(route);
            log.info("Loaded planned route {} with {} waypoints from {}",
                    route.routeId(), route.waypoints().size(), location);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load planned route from " + location, e);
        }
    }

    public PlannedRoute current() {
        return current.get();
    }

    static void validate(PlannedRoute route) {
        List<RouteWaypoint> waypoints = route.waypoints();
        List<RouteLeg> legs = route.legs();
        require(route.routeId() != null && !route.routeId().isBlank(), "route_id is required");
        require(waypoints != null && waypoints.size() >= 2, "at least two waypoints are required");
        require(legs != null && legs.size() == waypoints.size() - 1, "legs must connect consecutive waypoints");
        for (int i = 0; i < waypoints.size(); i++) {
            RouteWaypoint waypoint = waypoints.get(i);
            require(waypoint.seq() == i + 1, "waypoint seq must be consecutive from 1");
            require(waypoint.lon() >= LON_MIN && waypoint.lon() <= LON_MAX
                    && waypoint.lat() >= LAT_MIN && waypoint.lat() <= LAT_MAX, "waypoint out of bounding box");
            require(waypoint.turnRadiusM() >= 0, "turn_radius_m must be non-negative");
        }
        for (int i = 0; i < legs.size(); i++) {
            RouteLeg leg = legs.get(i);
            require(leg.fromSeq() == i + 1 && leg.toSeq() == i + 2, "leg must connect waypoint i and i+1");
            require(leg.speedKn() >= 0, "speed_kn must be non-negative");
            require(leg.xtdPortM() > 0 && leg.xtdStarboardM() > 0, "xtd limits must be positive");
            RouteWaypoint from = waypoints.get(i);
            RouteWaypoint to = waypoints.get(i + 1);
            require(from.lon() != to.lon() || from.lat() != to.lat(), "leg must have non-zero length");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Invalid planned route: " + message);
        }
    }
}
