package com.whut.map.map_service.navigation.route;

import java.util.List;

/**
 * 航线局部平面几何：以首个航点为参考做等距投影（演示航段 10 km 内误差可忽略）。
 */
public final class RouteGeometry {

    private static final double METERS_PER_DEGREE = 111320.0;

    private final double refLon;
    private final double refLat;
    private final double cosRefLat;
    private final double[] xs;
    private final double[] ys;
    private final double[] cumulative;

    private RouteGeometry(List<RouteWaypoint> waypoints) {
        RouteWaypoint ref = waypoints.get(0);
        this.refLon = ref.lon();
        this.refLat = ref.lat();
        this.cosRefLat = Math.cos(Math.toRadians(refLat));
        int n = waypoints.size();
        this.xs = new double[n];
        this.ys = new double[n];
        this.cumulative = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = toX(waypoints.get(i).lon());
            ys[i] = toY(waypoints.get(i).lat());
            if (i > 0) {
                cumulative[i] = cumulative[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
            }
        }
    }

    public static RouteGeometry of(PlannedRoute route) {
        return new RouteGeometry(route.waypoints());
    }

    public double totalLengthMeters() {
        return cumulative[cumulative.length - 1];
    }

    public RouteProjection project(double lon, double lat) {
        double px = toX(lon);
        double py = toY(lat);
        RouteProjection best = null;
        for (int i = 0; i < xs.length - 1; i++) {
            double dx = xs[i + 1] - xs[i];
            double dy = ys[i + 1] - ys[i];
            double length = Math.hypot(dx, dy);
            double ux = dx / length;
            double uy = dy / length;
            double rx = px - xs[i];
            double ry = py - ys[i];
            double t = Math.max(0.0, Math.min(length, rx * ux + ry * uy));
            double distance = Math.hypot(rx - t * ux, ry - t * uy);
            if (best == null || distance < best.distanceMeters()) {
                double signedOffset = ux * ry - uy * rx;
                best = new RouteProjection(i, cumulative[i] + t, signedOffset, distance);
            }
        }
        return best;
    }

    private double toX(double lon) {
        return (lon - refLon) * cosRefLat * METERS_PER_DEGREE;
    }

    private double toY(double lat) {
        return (lat - refLat) * METERS_PER_DEGREE;
    }
}
