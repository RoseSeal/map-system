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
}
