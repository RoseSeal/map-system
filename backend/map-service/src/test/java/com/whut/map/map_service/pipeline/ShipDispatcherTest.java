package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.assembler.LlmRiskContextAssembler;
import com.whut.map.map_service.assembler.RiskObjectAssembler;
import com.whut.map.map_service.domain.ShipRole;
import com.whut.map.map_service.domain.ShipStatus;
import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.collision.CpaTcpaBatchCalculator;
import com.whut.map.map_service.engine.risk.RiskAssessmentEngine;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.engine.safety.ShipDomainEngine;
import com.whut.map.map_service.engine.trajectoryprediction.CvPredictionEngine;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.service.llm.LlmTriggerService;
import com.whut.map.map_service.service.llm.RiskContextHolder;
import com.whut.map.map_service.store.ShipStateStore;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShipDispatcherTest {

    @Test
    void buildCurrentDistancesNmCalculatesDistancePerTarget() {
        ShipDispatcher dispatcher = dispatcher(new RiskContextHolder(), new RecordingRiskStreamPublisher(), new NoopLlmTriggerService());
        ShipStatus ownShip = ship("own-1", ShipRole.OWN_SHIP, 120.0000, 30.0000);
        ShipStatus target = ship("target-1", ShipRole.TARGET_SHIP, 120.1000, 30.1000);

        Map<String, Double> distances = dispatcher.buildCurrentDistancesNm(ownShip, List.of(ownShip, target));

        assertThat(distances).containsKey("target-1");
        assertThat(distances.get("target-1")).isGreaterThan(0.0);
    }

    @Test
    void publishRiskSnapshotRefreshesRiskContextHolder() {
        RiskContextHolder holder = new RiskContextHolder();
        RecordingRiskStreamPublisher publisher = new RecordingRiskStreamPublisher();
        ShipDispatcher dispatcher = dispatcher(holder, publisher, new NoopLlmTriggerService());
        LlmRiskContext context = LlmRiskContext.builder()
                .ownShip(LlmRiskOwnShipContext.builder().id("own-1").build())
                .build();
        RiskObjectDto riskObject = RiskObjectDto.builder().riskObjectId("risk-1").build();
        RiskDispatchSnapshot snapshot = new RiskDispatchSnapshot((RiskAssessmentResult) null, riskObject, context);

        dispatcher.publishRiskSnapshot(snapshot);

        assertThat(holder.getCurrent()).isSameAs(context);
        assertThat(holder.getUpdatedAt()).isNotNull();
        assertThat(publisher.publishedRiskObject).isSameAs(riskObject);
    }

    private ShipDispatcher dispatcher(
            RiskContextHolder holder,
            RecordingRiskStreamPublisher publisher,
            NoopLlmTriggerService triggerService
    ) {
        return new ShipDispatcher(
                (ShipDomainEngine) null,
                (CvPredictionEngine) null,
                (CpaTcpaBatchCalculator) null,
                (RiskAssessmentEngine) null,
                (RiskObjectAssembler) null,
                new LlmRiskContextAssembler(),
                triggerService,
                holder,
                (ShipStateStore) null,
                publisher
        );
    }

    private ShipStatus ship(String id, ShipRole role, double longitude, double latitude) {
        return ShipStatus.builder()
                .id(id)
                .role(role)
                .longitude(longitude)
                .latitude(latitude)
                .build();
    }

    private static final class RecordingRiskStreamPublisher extends RiskStreamPublisher {
        private RiskObjectDto publishedRiskObject;

        RecordingRiskStreamPublisher() {
            super(null, null);
        }

        @Override
        public void publishRiskUpdate(RiskObjectDto riskObject) {
            this.publishedRiskObject = riskObject;
        }
    }

    private static final class NoopLlmTriggerService extends LlmTriggerService {
        NoopLlmTriggerService() {
            super(new com.whut.map.map_service.config.properties.LlmProperties(), null, null);
        }

        @Override
        public void triggerExplanationsIfNeeded(LlmRiskContext context, String riskObjectId) {
        }
    }
}
