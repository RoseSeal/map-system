package com.whut.map.map_service.risk.engine;

import com.whut.map.map_service.source.ais.config.AisQualityProperties;
import com.whut.map.map_service.shared.domain.QualityFlag;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

@Component
public class ShipKinematicQualityChecker {

    private static final double DEDUCTION_POSITION_JUMP = 0.4;
    private static final double DEDUCTION_SOG_JUMP      = 0.2;
    private static final double DEDUCTION_COG_JUMP      = 0.15;

    private final AisQualityProperties properties;

    public ShipKinematicQualityChecker(AisQualityProperties properties) {
        this.properties = properties;
    }

    public ShipStatus check(ShipStatus current, ShipStatus previous) {
        if (previous == null) {
            return current;
        }
        if (previous.getMsgTime() == null || current.getMsgTime() == null) {
            return current;
        }

        // 使用 Duration 计算时间差，单位秒
        long dtSeconds = Duration.between(previous.getMsgTime(), current.getMsgTime()).getSeconds();
        if (dtSeconds <= 0) {
            return current;
        }

        Set<QualityFlag> newFlags = copyFlags(current.getQualityFlags());
        double deduction = 0.0;

        // 1. 位置跳变检查
        double distanceMeters = GeoUtils.distanceMetersByXY(
                previous.getLatitude(), previous.getLongitude(),
                current.getLatitude(), current.getLongitude());
        
        // V_actual: nm / h (knots)
        double vActualKn = GeoUtils.metersToNm(distanceMeters) / (dtSeconds / 3600.0);
        double maxAllowedKn = Math.max(current.getSog(), properties.getPositionJumpMinSpeedKn()) 
                * properties.getPositionJumpSpeedMultiplier();

        if (vActualKn > maxAllowedKn) {
            newFlags.add(QualityFlag.POSITION_JUMP);
            deduction += DEDUCTION_POSITION_JUMP;
        }

        // 2. 航速突变检查
        if (Math.abs(current.getSog() - previous.getSog()) > properties.getSogJumpThresholdKn()) {
            newFlags.add(QualityFlag.SOG_JUMP);
            deduction += DEDUCTION_SOG_JUMP;
        }

        // 3. 航向突变检查
        double diffCog = Math.abs(current.getCog() - previous.getCog());
        double deltaCog = Math.min(diffCog, 360.0 - diffCog);
        if (deltaCog > properties.getCogJumpThresholdDeg()) {
            newFlags.add(QualityFlag.COG_JUMP);
            deduction += DEDUCTION_COG_JUMP;
        }

        if (newFlags.equals(current.getQualityFlags()) && deduction == 0.0) {
            return current; // No changes
        }

        double oldConfidence = current.getConfidence() == null ? 1.0 : current.getConfidence();
        double newConfidence = Math.max(0.0, oldConfidence - deduction);

        return ShipStatus.builder()
                .id(current.getId())
                .role(current.getRole())
                .longitude(current.getLongitude())
                .latitude(current.getLatitude())
                .sog(current.getSog())
                .cog(current.getCog())
                .heading(current.getHeading())
                .msgTime(current.getMsgTime())
                .confidence(newConfidence)
                .qualityFlags(newFlags)
                .build();
    }

    private Set<QualityFlag> copyFlags(Set<QualityFlag> source) {
        if (source == null || source.isEmpty()) {
            return EnumSet.noneOf(QualityFlag.class);
        }
        return EnumSet.copyOf(source);
    }
}
