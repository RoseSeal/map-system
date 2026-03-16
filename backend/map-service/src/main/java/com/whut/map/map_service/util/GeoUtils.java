package com.whut.map.map_service.util;

public class GeoUtils {

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
        double sogMs = sogKnots * 0.514444; // 将节转换为米/秒
        double rad   = Math.toRadians(cogDeg);
        double vx    = Math.sin(rad) * sogMs;
        double vy    = Math.cos(rad) * sogMs;
        // 2. 返回速度矢量
        return new double[]{vx, vy};
    }

}
