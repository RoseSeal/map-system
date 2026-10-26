package com.whut.map.map_service.navigation.route;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 本船计划航线。legs.get(i) 连接 waypoints.get(i) 与 waypoints.get(i + 1)；
 * 字段语义参照 ECDIS 航线常见属性，不是 RTZ 序列化格式。
 */
public record PlannedRoute(
        @JsonProperty("route_id") String routeId,
        @JsonProperty("name") String name,
        @JsonProperty("synthetic") boolean synthetic,
        @JsonProperty("waypoints") List<RouteWaypoint> waypoints,
        @JsonProperty("legs") List<RouteLeg> legs
) {
}
