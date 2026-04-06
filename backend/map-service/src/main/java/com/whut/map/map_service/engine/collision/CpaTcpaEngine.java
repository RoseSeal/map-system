package com.whut.map.map_service.engine.collision;


import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.GeoUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CpaTcpaEngine {

    private static final double MIN_RELATIVE_SPEED_MS = 0.01; // 0.01 m/s

    public CpaTcpaResult calculate(ShipStatus ownShip, ShipStatus targetShip) {
        // 1. 获取相关数据
        String targetId = targetShip.getId();
        // 获取经纬度
        double[] ownXY = GeoUtils.toXY(ownShip.getLatitude(), ownShip.getLongitude());
        double[] targetXY = GeoUtils.toXY(targetShip.getLatitude(), targetShip.getLongitude());
        // 获取速度
        double[] ownV = GeoUtils.toVelocity(ownShip.getSog(), ownShip.getCog());
        double[] targetV = GeoUtils.toVelocity(targetShip.getSog(), targetShip.getCog());
        // 获取相对位置和速度
        double dpx = targetXY[0] - ownXY[0];
        double dpy = targetXY[1] - ownXY[1];
        double dvx = targetV[0] - ownV[0];
        double dvy = targetV[1] - ownV[1];
        // 2. 计算CPA和TCPA
        double currentDist = GeoUtils.distanceMetersByXY(
                ownShip.getLatitude(), ownShip.getLongitude(),
                targetShip.getLatitude(), targetShip.getLongitude()
        );
        double cpa;
        double tcpa;

        double vel2 = dvx * dvx + dvy * dvy; // 相对速度的平方
        if (vel2 < MIN_RELATIVE_SPEED_MS) {
            // 如果相对速度接近于零，认为TCPA无意义，保持当前
            tcpa = 0;
            cpa = currentDist;
        } else {
            tcpa = - (dpx * dvx + dpy * dvy) / vel2;
            if(tcpa <= 0) {
                // 若TCPA为负，说明目标正在远离，保持当前CPA距离
                // 若TCPA为零，说明目标正在以当前距离交会，保持当前CPA距离
                cpa = currentDist;
            } else {
                // 计算CPA距离
                cpa = Math.sqrt(
                        (dpx + dvx * tcpa) * (dpx + dvx * tcpa) +
                        (dpy + dvy * tcpa) * (dpy + dvy * tcpa)
                );
            }
        }


        // 3. 返回结果
        return CpaTcpaResult.builder()
                .targetMmsi(targetId)
                .cpaDistance(cpa)
                .tcpaTime(tcpa)
                .isApproaching(tcpa > 0)
                .build();
    }
}