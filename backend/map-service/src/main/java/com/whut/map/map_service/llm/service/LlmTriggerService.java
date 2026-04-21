package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTriggerService {
    private final LlmProperties llmProperties;
    private final ConcurrentHashMap<String, Instant> nextAllowedTimeMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RiskLevel> lastExplainedLevelMap = new ConcurrentHashMap<>();
    private final LlmExplanationService llmExplanationService;

    public void triggerExplanationsIfNeeded(
            LlmRiskContext context,
            Consumer<LlmExplanation> onExplanation,
            BiConsumer<LlmRiskTargetContext, LlmExplanationService.LlmExplanationError> onError
    ) {
        Objects.requireNonNull(onExplanation, "onExplanation must not be null");
        Objects.requireNonNull(onError, "onError must not be null");

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

        List<LlmRiskTargetContext> targets = context.getTargets();
        if (targets == null || targets.isEmpty()) {
            // Do not clear nextAllowedTimeMap here: a transient empty snapshot (polling gap,
            // state-store churn) must not reset cooldown state for targets that will reappear.
            log.debug("Skipping LLM explanation because no target context is available");
            return;
        }

        // Lazily evict stale entries: remove MMSIs no longer present in the current snapshot.
        Set<String> activeTargetIds = targets.stream()
                .map(LlmRiskTargetContext::getTargetId)
                .collect(Collectors.toSet());
        nextAllowedTimeMap.keySet().retainAll(activeTargetIds);
        lastExplainedLevelMap.keySet().retainAll(activeTargetIds);

        List<LlmExplanationService.ExplanationTrigger> triggeredTargets = targets.stream()
                .filter(this::isExplainableTarget)
                .map(this::buildExplanationTrigger)
                .filter(Objects::nonNull)
                .limit(llmProperties.getMaxTargetsPerCall())
                .toList();

        if (triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no targets passed the trigger filter");
            return;
        }

        Consumer<LlmExplanation> wrappedOnExplanation = explanation -> {
            RiskLevel level = RiskLevel.fromValue(explanation.getRiskLevel());
            if (level != null && explanation.getTargetId() != null) {
                lastExplainedLevelMap.put(explanation.getTargetId(), level);
            }
            onExplanation.accept(explanation);
        };

        llmExplanationService.generateTargetExplanationsAsync(
                ownShip,
                triggeredTargets,
                wrappedOnExplanation,
                onError
        );
    }

    private LlmExplanationService.ExplanationTrigger buildExplanationTrigger(LlmRiskTargetContext target) {
        RiskLevel lastExplainedLevel = lastExplainedLevelMap.get(target.getTargetId());
        if (isLevelUpgrade(target, lastExplainedLevel)) {
            // Reset cooldown timer so the next same-level frame uses the cooldown gate
            nextAllowedTimeMap.put(target.getTargetId(), Instant.now().plus(cooldown()));
            return new LlmExplanationService.ExplanationTrigger(
                    target,
                    LlmExplanationService.TriggerReason.LEVEL_UPGRADE,
                    lastExplainedLevel
            );
        }

        if (!tryAcquireTrigger(target)) {
            return null;
        }

        return new LlmExplanationService.ExplanationTrigger(
                target,
                LlmExplanationService.TriggerReason.COOLDOWN_REFRESH,
                lastExplainedLevel
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

    private boolean isLevelUpgrade(LlmRiskTargetContext target, RiskLevel lastExplainedLevel) {
        return lastExplainedLevel != null && target.getRiskLevel().compareTo(lastExplainedLevel) > 0;
    }

    private boolean isExplainableTarget(LlmRiskTargetContext target) {
        return target != null
                && target.getRiskLevel() != null
                && target.getTargetId() != null
                && target.getRiskLevel() != RiskLevel.SAFE;
    }

    private Duration cooldown() {
        return Duration.ofSeconds(llmProperties.getCooldownSeconds());
    }
}
