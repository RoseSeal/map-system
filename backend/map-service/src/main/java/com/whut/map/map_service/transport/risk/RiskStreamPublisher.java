package com.whut.map.map_service.transport.risk;

import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.sse.ExplanationPayload;
import com.whut.map.map_service.dto.sse.RiskUpdatePayload;
import com.whut.map.map_service.dto.sse.SseErrorPayload;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.transport.protocol.ProtocolConnections;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
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
    private final SseEventFactory sseEventFactory;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final ExecutorService publishExecutor = Executors.newSingleThreadExecutor(riskPublishThreadFactory());
    private volatile RiskFrameSnapshot latestRiskFrame;

    public void register(SseEmitter emitter) {
        submit("register risk SSE emitter", () -> {
            sseEmitterRegistry.register(emitter);
            replayLatestTo(emitter);
        });
    }

    public void publishRiskUpdate(RiskObjectDto riskObject) {
        submit("publish risk update", () -> {
            RiskUpdatePayload payload = sseEventFactory.buildRiskUpdate(riskObject);
            if (payload == null) {
                return;
            }

            publishAndCacheLatestRiskFrame(payload);
        });
    }

    public void publishExplanation(LlmExplanation explanation, String riskObjectId) {
        submit("publish explanation", () -> {
            ExplanationPayload payload = sseEventFactory.buildExplanation(explanation, riskObjectId);
            if (payload == null) {
                return;
            }

            publish(SseEventType.EXPLANATION, payload);
        });
    }

    public void publishError(ChatErrorCode errorCode, String errorMessage, String targetId) {
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

    private String resolveErrorCode(ChatErrorCode errorCode) {
        return errorCode == null ? ChatErrorCode.LLM_REQUEST_FAILED.getValue() : errorCode.getValue();
    }

    private void publishAndCacheLatestRiskFrame(RiskUpdatePayload payload) {
        RiskFrameSnapshot snapshot = buildSnapshot(SseEventType.RISK_UPDATE, payload);
        if (snapshot == null) {
            return;
        }

        latestRiskFrame = snapshot;
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
        RiskFrameSnapshot snapshot = latestRiskFrame;
        if (snapshot == null) {
            return;
        }

        sseEmitterRegistry.sendJson(emitter, snapshot.eventType(), snapshot.sequenceId(), snapshot.jsonPayload());
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
