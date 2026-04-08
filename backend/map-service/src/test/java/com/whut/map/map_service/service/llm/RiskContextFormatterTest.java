package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RiskContextFormatterTest {

    @Test
    void formatsOnlyNonSafeTargetsInRiskOrderAndAppliesTopN() {
        LlmProperties properties = new LlmProperties();
        properties.setChatContextMaxTargets(2);
        RiskContextFormatter formatter = new RiskContextFormatter(properties);
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder()
                        .id("own-1")
                        .longitude(120.1234)
                        .latitude(30.5678)
                        .sog(12.3)
                        .cog(87.6)
                        .build())
                .targets(List.of(
                        target("safe-1", RiskConstants.SAFE, 3.0, 2.5, 500, false),
                        target("warning-2", RiskConstants.WARNING, 0.9, 0.4, 240, true),
                        target("alarm-1", RiskConstants.ALARM, 1.1, 0.2, 120, true),
                        target("warning-1", RiskConstants.WARNING, 0.3, 0.5, 300, true)
                ))
                .build();

        String summary = formatter.formatSummary(context, Instant.parse("2026-04-04T10:30:00Z"));

        assertThat(summary)
                .contains("【当前态势】更新时间: 2026-04-04T10:30:00Z")
                .contains("本船 ID: own-1")
                .contains("目标船 alarm-1: 风险等级 ALARM")
                .contains("目标船 warning-1: 风险等级 WARNING")
                .doesNotContain("safe-1")
                .doesNotContain("warning-2")
                .contains("共追踪 4 艘目标船，展示 2 艘，未展示 2 艘。");
        assertThat(summary.indexOf("alarm-1")).isLessThan(summary.indexOf("warning-1"));
    }

    @Test
    void formatSelectedTargetsReturnsDetailedInfoIncludingSafeTargets() {
        LlmProperties properties = new LlmProperties();
        RiskContextFormatter formatter = new RiskContextFormatter(properties);
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder()
                        .id("own-1")
                        .longitude(120.1234)
                        .latitude(30.5678)
                        .sog(12.3)
                        .cog(87.6)
                        .build())
                .targets(List.of(
                        detailedTarget("safe-1", RiskConstants.SAFE, 3.0, 2.5, 500, false,
                                121.0, 31.0, 10.0, 180.0, null),
                        detailedTarget("warning-1", RiskConstants.WARNING, 0.9, 0.4, 240, true,
                                121.5, 31.5, 8.0, 45.0, "目标船正从右舷接近")
                ))
                .build();

        String result = formatter.formatSelectedTargets(context,
                List.of("safe-1", "warning-1"),
                Instant.parse("2026-04-04T10:30:00Z"));

        assertThat(result)
                .contains("【选中目标详情】更新时间: 2026-04-04T10:30:00Z")
                .contains("本船 ID: own-1")
                .contains("目标船 safe-1: 风险等级 SAFE")
                .contains("目标船 warning-1: 风险等级 WARNING")
                .contains("位置: (121.5000, 31.5000)")
                .contains("航速: 8.0节")
                .contains("航向: 45.0°")
                .contains("说明: 目标船正从右舷接近")
                .contains("共选中 2 艘，匹配 2 艘。");
        assertThat(result.indexOf("safe-1")).isLessThan(result.indexOf("warning-1"));
    }

    @Test
    void formatSelectedTargetsSkipsUnmatchedIdsAndReturnsNullWhenNoneMatch() {
        LlmProperties properties = new LlmProperties();
        RiskContextFormatter formatter = new RiskContextFormatter(properties);
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .targets(List.of(target("target-1", RiskConstants.WARNING, 1.0, 0.5, 200, true)))
                .build();

        String partial = formatter.formatSelectedTargets(context,
                List.of("target-1", "nonexistent"),
                Instant.now());
        assertThat(partial)
                .contains("目标船 target-1")
                .doesNotContain("nonexistent")
                .contains("共选中 2 艘，匹配 1 艘。");

        String none = formatter.formatSelectedTargets(context,
                List.of("nonexistent"),
                Instant.now());
        assertThat(none).isNull();
    }

    @Test
    void formatSelectedTargetsReturnsNullForEmptyOrNullInput() {
        LlmProperties properties = new LlmProperties();
        RiskContextFormatter formatter = new RiskContextFormatter(properties);
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .targets(List.of(target("target-1", RiskConstants.WARNING, 1.0, 0.5, 200, true)))
                .build();

        assertThat(formatter.formatSelectedTargets(context, List.of(), Instant.now())).isNull();
        assertThat(formatter.formatSelectedTargets(context, null, Instant.now())).isNull();
        assertThat(formatter.formatSelectedTargets(null, List.of("target-1"), Instant.now())).isNull();
    }

    @Test
    void returnsNullWhenNoVisibleTargetsExist() {
        LlmProperties properties = new LlmProperties();
        RiskContextFormatter formatter = new RiskContextFormatter(properties);
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .targets(List.of(target("safe-1", RiskConstants.SAFE, 3.0, 2.5, 500, false)))
                .build();

        assertThat(formatter.formatSummary(context, Instant.now())).isNull();
    }

    private LlmRiskTargetContext target(
            String targetId,
            String riskLevel,
            double currentDistanceNm,
            double dcpaNm,
            double tcpaSec,
            boolean approaching
    ) {
        return LlmRiskTargetContext.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .currentDistanceNm(currentDistanceNm)
                .dcpaNm(dcpaNm)
                .tcpaSec(tcpaSec)
                .approaching(approaching)
                .build();
    }

    private LlmRiskTargetContext detailedTarget(
            String targetId,
            String riskLevel,
            double currentDistanceNm,
            double dcpaNm,
            double tcpaSec,
            boolean approaching,
            double longitude,
            double latitude,
            double speedKn,
            double courseDeg,
            String ruleExplanation
    ) {
        return LlmRiskTargetContext.builder()
                .targetId(targetId)
                .riskLevel(riskLevel)
                .currentDistanceNm(currentDistanceNm)
                .dcpaNm(dcpaNm)
                .tcpaSec(tcpaSec)
                .approaching(approaching)
                .longitude(longitude)
                .latitude(latitude)
                .speedKn(speedKn)
                .courseDeg(courseDeg)
                .ruleExplanation(ruleExplanation)
                .build();
    }
}
