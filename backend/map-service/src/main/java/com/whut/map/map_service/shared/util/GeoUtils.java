package com.whut.map.map_service.shared.util;

public class GeoUtils {

    public static final double METERS_PER_NAUTICAL_MILE = 1852.0;
    public static final double METERS_PER_KNOT = 0.514444;

    private GeoUtils() {
        // 私有构造函数，防止外部实例化
    }

    public static double[] toXY(double lat, double lon) {
        // 1. 将经纬度转换为平面坐标（等距投影，误差小于0.5%）
        double x = lon * Math.cos(Math.toRadians(lat)) * 111320.0;
        double y = lat * 111320.0;
        // 2. 返回转换后的坐标
        return new double[]{x, y};
    }

    public static double[] toVelocity(double sogKnots, double cogDeg) {
        // 1. 将速度和航向转换为速度矢量
        double sogMs = sogKnots * METERS_PER_KNOT; // 将节转换为米/秒
        double rad   = Math.toRadians(cogDeg);
        double vx    = Math.sin(rad) * sogMs;
        double vy    = Math.cos(rad) * sogMs;
        // 2. 返回速度矢量
        return new double[]{vx, vy};
    }

    public static double[] displace(double refLat, double refLon, double eastMeters, double northMeters) {
        double dLat = northMeters / 111320.0;
        double dLon = eastMeters / (Math.cos(Math.toRadians(refLat)) * 111320.0);
        return new double[]{refLat + dLat, refLon + dLon};
    }

    public static double metersToNm(double meters) {
        return meters / METERS_PER_NAUTICAL_MILE;
    }

    public static double nmToMeters(double nm) {
        return nm * METERS_PER_NAUTICAL_MILE;
    }

    public static double distanceMetersByXY(double lat1, double lon1, double lat2, double lon2) {
        double[] p1 = toXY(lat1, lon1);
        double[] p2 = toXY(lat2, lon2);
        double dx = p2[0] - p1[0];
        double dy = p2[1] - p1[1];
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static double trueBearing(double lat1, double lon1, double lat2, double lon2) {
        double meanLatRad = Math.toRadians((lat1 + lat2) / 2.0);
        double dx = (lon2 - lon1) * Math.cos(meanLatRad) * 111320.0;
        double dy = (lat2 - lat1) * 111320.0;
        double bearing = Math.toDegrees(Math.atan2(dx, dy));
        return bearing >= 0 ? bearing : bearing + 360.0;
    }

    /**
     * 返回两个航向角之间的最小角度差，范围 [0, 180]。
     * 示例：angleDifference(10, 350) == 20；angleDifference(0, 180) == 180。
     */
    public static double angleDifference(double a, double b) {
        double diff = Math.abs(((a - b) % 360.0 + 360.0) % 360.0);
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    public static String bearingSectorLabel(double relativeBearingDeg) {
        double normalized = ((relativeBearingDeg % 360.0) + 360.0) % 360.0;
        if (normalized >= 337.5 || normalized < 22.5) {
            return "正前方";
        }
        if (normalized < 67.5) {
            return "右舷前方";
        }
        if (normalized < 112.5) {
            return "右舷正横";
        }
        if (normalized < 157.5) {
            return "右舷后方";
        }
        if (normalized < 202.5) {
            return "正后方";
        }
        if (normalized < 247.5) {
            return "左舷后方";
        }
        if (normalized < 292.5) {
            return "左舷正横";
        }
        return "左舷前方";
    }

}
