package com.whut.map.map_service.navigation.route;

/**
 * 点在航线上的投影。signedOffsetMeters 为相对所在航段方向的垂直偏移，左舷为正；
 * distanceMeters 为点到航线的最短距离（投影落在航段端点时两者不同）。
 */
public record RouteProjection(
        int legIndex,
        double alongMeters,
        double signedOffsetMeters,
        double distanceMeters
) {
}
