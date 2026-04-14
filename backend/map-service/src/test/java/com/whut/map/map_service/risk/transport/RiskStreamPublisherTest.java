package com.whut.map.map_service.risk.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.risk.event.RiskFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RiskStreamPublisherTest {

    @Test
    void publishRiskFrameRunsBeforePublishBeforeBroadcast() throws Exception {
        RecordingSseEmitterRegistry registry = new RecordingSseEmitterRegistry();
        RiskStreamPublisher publisher = new RiskStreamPublisher(new SseEventFactory(), registry);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        registry.prepare(order, done);

        try {
            publisher.publishRiskFrame(new RiskFrame(
                    RiskObjectDto.builder()
                            .riskObjectId("risk-1")
                            .timestamp("2026-04-11T10:00:00Z")
                            .build(),
                    version -> order.add("before:" + version)
            ));

            assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(order).containsExactly("before:1", "broadcast:RISK_UPDATE");
        } finally {
            publisher.shutdownExecutor();
        }
    }

    private static final class RecordingSseEmitterRegistry extends SseEmitterRegistry {
        private List<String> order;
        private CountDownLatch done;

        RecordingSseEmitterRegistry() {
            super(new ObjectMapper());
        }

        void prepare(List<String> order, CountDownLatch done) {
            this.order = order;
            this.done = done;
        }

        @Override
        public void broadcastJson(SseEventType eventType, String sequenceId, String jsonPayload) {
            order.add("broadcast:" + eventType.name());
            done.countDown();
        }
    }
}
