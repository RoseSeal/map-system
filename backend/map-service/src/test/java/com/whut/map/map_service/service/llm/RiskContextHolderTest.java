package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskContextHolderTest {

    @Test
    void updateStoresContextAndTimestamp() {
        RiskContextHolder holder = new RiskContextHolder();
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .build();

        holder.update(context);

        assertThat(holder.getCurrent()).isSameAs(context);
        assertThat(holder.getUpdatedAt()).isNotNull();
    }

    @Test
    void latestUpdateWins() {
        RiskContextHolder holder = new RiskContextHolder();
        LlmRiskContext first = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .build();
        LlmRiskContext second = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-2").build())
                .build();

        holder.update(first);
        holder.update(second);

        assertThat(holder.getCurrent()).isSameAs(second);
        assertThat(holder.getCurrent().getOwnShip().getId()).isEqualTo("own-2");
        assertThat(holder.getUpdatedAt()).isNotNull();
    }
}
