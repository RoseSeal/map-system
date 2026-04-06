package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
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
    private final RiskStreamPublisher riskStreamPublisher;

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
                explanation -> riskStreamPublisher.publishExplanation(explanation, riskObjectId),
                (target, error) -> publishError(target, error)
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

    private void publishError(
            LlmRiskTargetContext target,
            LlmExplanationService.LlmExplanationError error
    ) {
        if (error == null) {
            return;
        }

        String targetId = target == null ? null : target.getTargetId();
        riskStreamPublisher.publishError(resolveErrorCode(error.errorCode()), error.errorMessage(), targetId);
    }

    private ChatErrorCode resolveErrorCode(ChatErrorCode errorCode) {
        return errorCode == null ? ChatErrorCode.LLM_REQUEST_FAILED : errorCode;
    }

    private Duration cooldown() {
        return Duration.ofSeconds(llmProperties.getCooldownSeconds());
    }
}
