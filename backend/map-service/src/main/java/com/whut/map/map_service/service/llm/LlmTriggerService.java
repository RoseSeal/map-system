package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.engine.risk.RiskConstants;
import com.whut.map.map_service.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmTriggerService {

    private final LlmProperties llmProperties;
    private final ConcurrentHashMap<String, Instant> nextAllowedTimeMap = new ConcurrentHashMap<>();
    private final LlmExplanationService llmExplanationService;

    public Map<String, LlmExplanation> triggerExplanationsIfNeeded(LlmRiskContext context) {
        // 如果LLM功能未启用，直接返回空解释
        if (!llmProperties.isEnabled()) {
            log.debug("Skipping LLM explanation because llm.enabled=false");
            return null;
        }

        // 如果没有有效的目标上下文，直接返回空解释
        if (context == null || context.getTargets() == null || context.getTargets().isEmpty()) {
            log.debug("Skipping LLM explanation because no target context is available");
            return null;
        }



        // 过滤得到最终目标船只列表：满足冷却时间要求、风险等级可解释、且在每次调用的最大目标数量限制内
        List<LlmRiskTargetContext> triggeredTargets = context.getTargets().stream()
                .filter(this::isExplainableTarget)
                .filter(this::tryAcquireTrigger)
                .limit(llmProperties.getMaxTargetsPerCall())
                .toList();
        return llmExplanationService.generateTargetExplanations(context.getOwnShip() , triggeredTargets);
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

    private Duration cooldown() {
        return Duration.ofSeconds(llmProperties.getCooldownSeconds());
    }
}
