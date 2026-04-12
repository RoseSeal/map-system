package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for encounter classification.
 */
@Data
@Component
@ConfigurationProperties(prefix = "engine.encounter")
public class EncounterProperties {
    /** 对遇：目标相对本船船头半角（°），默认 ±15°。 */
    private double headOnBowHalfAngleDeg       = 15.0;
    /** 对遇：两船航向差最小值（°），默认 170°（互为逆向 ±10°）。 */
    private double headOnCourseDiffMinDeg      = 170.0;
    /** 追越：目标相对本船船尾半角（°），默认 ±67.5°（COLREGS 22.5° abaft beam）。 */
    private double overtakingSternHalfAngleDeg = 67.5;
    /** 追越：两船航向差最大值（°），默认 112.5°（排除反向或大角度交叉）。 */
    private double overtakingCourseDiffMaxDeg  = 112.5;
}
