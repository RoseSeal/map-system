package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import com.whut.map.map_service.dto.llm.LlmRiskOwnShipContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.dto.sse.ExplanationPayload;
import com.whut.map.map_service.dto.sse.SseErrorPayload;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.websocket.ProtocolConnections;
import com.whut.map.map_service.websocket.SseEmitterRegistry;
import com.whut.map.map_service.websocket.SseEventFactory;
import com.whut.map.map_service.websocket.SseEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTriggerService {
    private final LlmProperties llmProperties;
    private final ConcurrentHashMap<String, Instant> nextAllowedTimeMap = new ConcurrentHashMap<>();
    private final LlmExplanationService llmExplanationService;
    private final SseEmitterRegistry sseEmitterRegistry;
    private final SseEventFactory sseEventFactory;

    public void triggerExplanationsIfNeeded(LlmRiskContext context, String riskObjectId) {
        if (!llmProperties.isEnabled()) {
            log.debug("Skipping LLM explanation because llm.enabled=false");
            return;
        }

        if (context == null) {
            log.debug("Skipping LLM explanation because context is null");
            return;
        }

        LlmRiskOwnShipContext ownShip = context.getOwnShip();
        if (ownShip == null) {
            log.debug("Skipping LLM explanation because ownShip is null");
            return;
        }

        if (context.getTargets() == null || context.getTargets().isEmpty()) {
            log.debug("Skipping LLM explanation because no target context is available");
            return;
        }

        List<LlmRiskTargetContext> triggeredTargets = context.getTargets().stream()
                .filter(this::isExplainableTarget)
                .filter(this::tryAcquireTrigger)
                .limit(llmProperties.getMaxTargetsPerCall())
                .toList();

        if (triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no targets passed the trigger filter");
            return;
        }

        llmExplanationService.generateTargetExplanationsAsync(
                ownShip,
                triggeredTargets,
                explanation -> broadcastExplanation(explanation, riskObjectId),
                (target, error) -> broadcastError(target, error)
        );
    }

    private boolean tryAcquireTrigger(LlmRiskTargetContext target) {
        Instant now = Instant.now();
        Instant nextAllowedTime = now.plus(cooldown());

        AtomicBoolean acquired = new AtomicBoolean(false);

        nextAllowedTimeMap.compute(target.getTargetId(), (id, existingNextAllowedTime) -> {
            if (existingNextAllowedTime != null && now.isBefore(existingNextAllowedTime)) {
                return existingNextAllowedTime;
            }

            acquired.set(true);
            return nextAllowedTime;
        });

        return acquired.get();
    }

    private boolean isExplainableTarget(LlmRiskTargetContext target) {
        return target != null
                && target.getRiskLevel() != null
                && target.getTargetId() != null
                && !RiskConstants.SAFE.equals(target.getRiskLevel());
    }

    private void broadcastExplanation(LlmExplanation explanation, String riskObjectId) {
        ExplanationPayload payload = sseEventFactory.buildExplanation(explanation, riskObjectId);
        if (payload == null) {
            return;
        }
        sseEmitterRegistry.broadcast(SseEventType.EXPLANATION, payload.getEventId(), payload);
    }

    private void broadcastError(
            LlmRiskTargetContext target,
            LlmExplanationService.LlmExplanationError error
    ) {
        if (error == null) {
            return;
        }

        String eventId = sseEventFactory.generateEventId();
        String errorMessage = error.errorMessage();
        if (target != null && target.getTargetId() != null) {
            errorMessage = errorMessage + " target_id=" + target.getTargetId();
        }

        SseErrorPayload payload = SseErrorPayload.builder()
                .eventId(eventId)
                .connection(ProtocolConnections.RISK)
                .errorCode(resolveErrorCode(error.errorCode()))
                .errorMessage(errorMessage)
                .replyToEventId(null)
                .timestamp(Instant.now().toString())
                .build();
        sseEmitterRegistry.broadcast(SseEventType.ERROR, eventId, payload);
    }

    private String resolveErrorCode(ChatErrorCode errorCode) {
        return errorCode == null ? ChatErrorCode.LLM_REQUEST_FAILED.getValue() : errorCode.getValue();
    }

    private Duration cooldown() {
        return Duration.ofSeconds(llmProperties.getCooldownSeconds());
    }
}
