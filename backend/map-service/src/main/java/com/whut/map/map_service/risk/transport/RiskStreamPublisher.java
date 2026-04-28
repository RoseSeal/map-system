package com.whut.map.map_service.risk.transport;

import com.whut.map.map_service.risk.environment.EnvironmentStateSnapshot;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.risk.event.RiskFrame;
import com.whut.map.map_service.shared.dto.sse.AdvisoryPayload;
import com.whut.map.map_service.shared.dto.sse.EnvironmentUpdatePayload;
import com.whut.map.map_service.shared.dto.sse.ExplanationPayload;
import com.whut.map.map_service.shared.dto.sse.RiskUpdatePayload;
import com.whut.map.map_service.shared.dto.sse.SseErrorPayload;
import com.whut.map.map_service.shared.transport.protocol.ProtocolConnections;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class RiskStreamPublisher {

    private final AtomicLong sequence = new AtomicLong(0L);
    private final AtomicLong snapshotVersion = new AtomicLong(0L);
    private final SseEventFactory sseEventFactory;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(riskPublishThreadFactory());
    private volatile RiskFrameSnapshot latestRiskFrame;
    private volatile RiskFrameSnapshot latestEnvironmentFrame;

    public void register(SseEmitter emitter) {
        submit("register risk SSE emitter", () -> {
            sseEmitterRegistry.register(emitter);
            replayLatestTo(emitter);
        });
    }

    public void publishRiskFrame(RiskFrame frame) {
        submit("publish risk frame", () -> {
            if (frame == null || frame.riskObject() == null) {
                return;
            }

            long version = snapshotVersion.incrementAndGet();
            // Run the callback on the publisher thread so context refresh and risk-frame publish
            // share one server-side ordering point. This does not imply client-side delivery ACK.
            if (frame.beforePublish() != null) {
                frame.beforePublish().accept(version);
            }

            RiskUpdatePayload payload = sseEventFactory.buildRiskUpdate(frame.riskObject());
            if (payload == null) {
                return;
            }

            publishAndCacheLatestRiskFrame(payload);
        });
    }

    public void publishEnvironmentUpdate(
            EnvironmentStateSnapshot snapshot,
            EnvironmentUpdateReason reason,
            List<String> changedFields
    ) {
        submit("publish environment update", () -> {
            EnvironmentUpdatePayload payload = sseEventFactory.buildEnvironmentUpdate(snapshot, reason, changedFields);
            if (payload == null) {
                return;
            }

            publishAndCacheLatestEnvironmentFrame(payload);
        });
    }

    public void publishAdvisory(AdvisoryPayload payload) {
        submit("publish advisory", () -> {
            if (payload == null) {
                return;
            }

            publish(SseEventType.ADVISORY, payload);
        });
    }

    public void publishExplanation(ExplanationPayload payload) {
        submit("publish explanation", () -> {
            if (payload == null) {
                return;
            }

            publish(SseEventType.EXPLANATION, payload);
        });
    }

    public void publishError(String errorCode, String errorMessage, String targetId) {
        submit("publish risk error", () -> {
            String payloadErrorMessage = errorMessage;
            if (targetId != null && !targetId.isBlank()) {
                payloadErrorMessage = payloadErrorMessage + " target_id=" + targetId;
            }

            SseErrorPayload payload = SseErrorPayload.builder()
                    .eventId(sseEventFactory.generateEventId())
                    .connection(ProtocolConnections.RISK)
                    .errorCode(resolveErrorCode(errorCode))
                    .errorMessage(payloadErrorMessage)
                    .replyToEventId(null)
                    .timestamp(Instant.now().toString())
                    .build();

            publish(SseEventType.ERROR, payload);
        });
    }

    private String nextSequenceId() {
        return String.valueOf(sequence.incrementAndGet());
    }

    private String resolveErrorCode(String errorCode) {
        return errorCode == null || errorCode.isBlank() ? "LLM_REQUEST_FAILED" : errorCode;
    }

    private void publishAndCacheLatestRiskFrame(RiskUpdatePayload payload) {
        RiskFrameSnapshot snapshot = buildSnapshot(SseEventType.RISK_UPDATE, payload);
        if (snapshot == null) {
            return;
        }

        latestRiskFrame = snapshot;
        sseEmitterRegistry.broadcastJson(snapshot.eventType(), snapshot.sequenceId(), snapshot.jsonPayload());
    }

    private void publishAndCacheLatestEnvironmentFrame(EnvironmentUpdatePayload payload) {
        RiskFrameSnapshot snapshot = buildSnapshot(SseEventType.ENVIRONMENT_UPDATE, payload);
        if (snapshot == null) {
            return;
        }

        latestEnvironmentFrame = snapshot;
        sseEmitterRegistry.broadcastJson(snapshot.eventType(), snapshot.sequenceId(), snapshot.jsonPayload());
    }

    private void publish(SseEventType eventType, Object payload) {
        RiskFrameSnapshot snapshot = buildSnapshot(eventType, payload);
        if (snapshot == null) {
            return;
        }

        sseEmitterRegistry.broadcastJson(snapshot.eventType(), snapshot.sequenceId(), snapshot.jsonPayload());
    }

    private RiskFrameSnapshot buildSnapshot(SseEventType eventType, Object payload) {
        if (payload == null) {
            return null;
        }

        String jsonPayload = serialize(payload, eventType);
        if (jsonPayload == null) {
            return null;
        }

        return new RiskFrameSnapshot(eventType, nextSequenceId(), jsonPayload);
    }

    private String serialize(Object payload, SseEventType eventType) {
        return sseEmitterRegistry.serialize(payload, eventType);
    }

    private void replayLatestTo(SseEmitter emitter) {
        RiskFrameSnapshot environmentSnapshot = latestEnvironmentFrame;
        if (environmentSnapshot != null) {
            sseEmitterRegistry.sendJson(
                    emitter,
                    environmentSnapshot.eventType(),
                    environmentSnapshot.sequenceId(),
                    environmentSnapshot.jsonPayload()
            );
        }

        RiskFrameSnapshot riskSnapshot = latestRiskFrame;
        if (riskSnapshot != null) {
            sseEmitterRegistry.sendJson(
                    emitter,
                    riskSnapshot.eventType(),
                    riskSnapshot.sequenceId(),
                    riskSnapshot.jsonPayload()
            );
        }
    }

    private void submit(String action, Runnable task) {
        try {
            publishExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            log.warn("Skipping {} because risk publisher is shutting down", action);
        }
    }

    @PreDestroy
    public void shutdownExecutor() {
        publishExecutor.shutdown();
        try {
            if (!publishExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                publishExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publishExecutor.shutdownNow();
        }
    }

    private static ThreadFactory riskPublishThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "risk-stream-publisher");
            thread.setDaemon(true);
            return thread;
        };
    }

    private record RiskFrameSnapshot(
            SseEventType eventType,
            String sequenceId,
            String jsonPayload
    ) {
    }
}
