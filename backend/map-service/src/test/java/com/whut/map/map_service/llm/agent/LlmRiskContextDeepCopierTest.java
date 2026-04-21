package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRiskContextDeepCopierTest {

    private final LlmRiskContextDeepCopier copier = new LlmRiskContextDeepCopier();

    @Test
    void modifyingCopiedTargetDoesNotAffectSource() {
        LlmRiskTargetContext sourceTarget = LlmRiskTargetContext.builder()
                .targetId("t1")
                .riskLevel(RiskLevel.WARNING)
                .tcpaSec(120.0)
                .build();
        LlmRiskContext source = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").sog(10.0).cog(90.0).build())
                .targets(List.of(sourceTarget))
                .build();

        LlmRiskContext copy = copier.copy(source);
        copy.getTargets().get(0).setRiskLevel(RiskLevel.ALARM);
        copy.getTargets().get(0).setTcpaSec(60.0);

        assertThat(source.getTargets().get(0).getRiskLevel()).isEqualTo(RiskLevel.WARNING);
        assertThat(source.getTargets().get(0).getTcpaSec()).isEqualTo(120.0);
    }

    @Test
    void modifyingCopiedOwnShipDoesNotAffectSource() {
        LlmRiskOwnShipContext ownShip = LlmRiskOwnShipContext.builder()
                .id("own")
                .sog(12.0)
                .cog(90.0)
                .build();
        LlmRiskContext source = LlmRiskContext.builder()
                .ownShip(ownShip)
                .targets(List.of())
                .build();

        LlmRiskContext copy = copier.copy(source);
        copy.getOwnShip().setSog(99.0);

        assertThat(source.getOwnShip().getSog()).isEqualTo(12.0);
    }

    @Test
    void copiedTargetListIsIndependentFromSourceList() {
        LlmRiskContext source = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own").sog(0).cog(0).build())
                .targets(List.of(
                        LlmRiskTargetContext.builder().targetId("t1").riskLevel(RiskLevel.CAUTION).build()
                ))
                .build();

        LlmRiskContext copy = copier.copy(source);

        assertThat(copy.getTargets()).hasSize(1);
        assertThat(copy.getTargets().get(0)).isNotSameAs(source.getTargets().get(0));
        assertThat(copy.getOwnShip()).isNotSameAs(source.getOwnShip());
    }

    @Test
    void copyOfNullReturnsNull() {
        assertThat(copier.copy(null)).isNull();
    }
}
