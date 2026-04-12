package com.whut.map.map_service.engine.encounter;

import com.whut.map.map_service.config.properties.EncounterProperties;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.util.GeoUtils;
import org.springframework.stereotype.Component;

/**
 * Classifier for encounter situations between two ships.
 */
@Component
public class EncounterClassifier {

    // AIS COG 无效哨兵：360.0 = "not available"（ITU-R M.1371）
    private static final double COG_NOT_AVAILABLE = 360.0;

    private final EncounterProperties props;

    public EncounterClassifier(EncounterProperties props) {
        this.props = props;
    }

    public EncounterClassificationResult classify(ShipStatus ownShip, ShipStatus targetShip) {
        double ownCog = ownShip.getCog();
        double tgtCog = targetShip.getCog();

        // COG 无效 → UNDEFINED（NaN / 负值 / >= 360.0）
        if (isInvalidCog(ownCog) || isInvalidCog(tgtCog)) {
            return EncounterClassificationResult.builder()
                    .targetId(targetShip.getId())
                    .encounterType(EncounterType.UNDEFINED)
                    .relativeBearingDeg(0.0)
                    .courseDifferenceDeg(0.0)
                    .build();
        }

        // 本船到目标船的真方位（[0, 360)）
        double bearingOwnToTarget = GeoUtils.trueBearing(
                ownShip.getLatitude(), ownShip.getLongitude(),
                targetShip.getLatitude(), targetShip.getLongitude());

        // 目标相对本船船头的方位角（[0, 360)，以本船 COG 为参考轴）
        double relBearing = normalizeAngle360(bearingOwnToTarget - ownCog);

        // 两船航向最小差（[0, 180]）
        double courseDiff = GeoUtils.angleDifference(ownCog, tgtCog);

        // ── HEAD-ON ──────────────────────────────────────────────────────────
        // 条件 1：两船航向差 >= headOnCourseDiffMinDeg（互为逆向）
        // 条件 2：目标在本船船头 ±headOnBowHalfAngleDeg 范围内
        // 条件 3：本船在目标船船头 ±headOnBowHalfAngleDeg 范围内（双向验证）
        if (courseDiff >= props.getHeadOnCourseDiffMinDeg()
                && isWithinBow(relBearing, props.getHeadOnBowHalfAngleDeg())) {
            double bearingTgtToOwn = normalizeAngle360(bearingOwnToTarget + 180.0);
            double relBearingFromTarget = normalizeAngle360(bearingTgtToOwn - tgtCog);
            if (isWithinBow(relBearingFromTarget, props.getHeadOnBowHalfAngleDeg())) {
                return build(targetShip.getId(), EncounterType.HEAD_ON, relBearing, courseDiff);
            }
        }

        // ── OVERTAKING ───────────────────────────────────────────────────────
        // 条件 1（几何）：目标在本船正后方 ±overtakingSternHalfAngleDeg
        // 条件 2（动力）：两船航向差 <= overtakingCourseDiffMaxDeg（大致同向）
        // 条件 2 排除"在船尾弧内但反向航行"的反向交叉场景
        if (isWithinStern(relBearing, props.getOvertakingSternHalfAngleDeg())
                && courseDiff <= props.getOvertakingCourseDiffMaxDeg()) {
            return build(targetShip.getId(), EncounterType.OVERTAKING, relBearing, courseDiff);
        }

        // ── CROSSING ─────────────────────────────────────────────────────────
        // 兜底：几何上不满足对遇或追越条件的其余有效态势
        return build(targetShip.getId(), EncounterType.CROSSING, relBearing, courseDiff);
    }

    // target 在本船船头 ±halfAngle 范围（relBearing ∈ [0, halfAngle] ∪ [360-halfAngle, 360)）
    private static boolean isWithinBow(double relBearing, double halfAngle) {
        return relBearing <= halfAngle || relBearing >= (360.0 - halfAngle);
    }

    // target 在本船船尾 ±halfAngle 范围（relBearing ∈ [180-halfAngle, 180+halfAngle]）
    private static boolean isWithinStern(double relBearing, double halfAngle) {
        return relBearing >= (180.0 - halfAngle) && relBearing <= (180.0 + halfAngle);
    }

    private static double normalizeAngle360(double angle) {
        return ((angle % 360.0) + 360.0) % 360.0;
    }

    private static boolean isInvalidCog(double cog) {
        return Double.isNaN(cog) || cog < 0.0 || cog >= COG_NOT_AVAILABLE;
    }

    private static EncounterClassificationResult build(
            String targetId, EncounterType type, double relBearing, double courseDiff) {
        return EncounterClassificationResult.builder()
                .targetId(targetId)
                .encounterType(type)
                .relativeBearingDeg(relBearing)
                .courseDifferenceDeg(courseDiff)
                .build();
    }
}
