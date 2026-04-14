package com.whut.map.map_service.shared.util;

import org.locationtech.jts.geom.Envelope;

/**
 * 瓦片工具类：负责 Web Mercator (EPSG:3857) 的坐标计算。
 * 包含了 "Slippy Map" 的标准转换公式。
 */
public class

TileUtils {

    // 地球半径 (Web Mercator)
    private static final double EARTH_RADIUS = 6378137.0;
    // 地球周长
    private static final double EARTH_CIRCUMFERENCE = 2 * Math.PI * EARTH_RADIUS;
    // Web Mercator 的最大范围 (X 和 Y 的边界)
    private static final double MAX_EXTENT = 20037508.3427892;

    /**
     * 根据瓦片编号 (z, x, y) 计算该瓦片在 EPSG:3857 (Web Mercator) 坐标系下的边界范围。
     * 这是 PostGIS ST_AsMVTGeom 函数所需的关键参数。
     *
     * @param x 瓦片 X 编号
     * @param y 瓦片 Y 编号
     * @param z 缩放级别 (Zoom Level)
     * @return JTS Envelope 对象 (MinX, MaxX, MinY, MaxY)
     */
    public static Envelope tileToEnvelope(int x, int y, int z) {
        // 1. 计算该缩放级别下的瓦片总数 (2^z)
        double tileCount = Math.pow(2, z);

        // 2. 计算单个瓦片的边长 (在 EPSG:3857 单位下，即米)
        double tileSize = EARTH_CIRCUMFERENCE / tileCount;

        // 3. 计算左下角 (Min) 和右上角 (Max) 坐标
        // 原点在左上角，X 向右增加，Y 向下增加 (但在 Mercator 坐标系中 Y 是向上增加的，所以需要反转)

        // MinX: 从最左边 (-MAX_EXTENT) 开始，加上 x 个瓦片的宽度
        double minX = -MAX_EXTENT + (x * tileSize);

        // MaxX: MinX 加上一个瓦片的宽度
        double maxX = minX + tileSize;

        // MaxY: 从最上边 (MAX_EXTENT) 开始，减去 y 个瓦片的高度 (注意 Y 轴方向)
        double maxY = MAX_EXTENT - (y * tileSize);

        // MinY: MaxY 减去一个瓦片的高度
        double minY = maxY - tileSize;

        return new Envelope(minX, maxX, minY, maxY);
    }
}