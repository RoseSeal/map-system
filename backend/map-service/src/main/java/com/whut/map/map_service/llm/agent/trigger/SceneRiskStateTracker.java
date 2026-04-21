package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class SceneRiskStateTracker {

    private final LlmProperties llmProperties;
    private final AgentSnapshotFactory agentSnapshotFactory;
    private final ObjectProvider<AdvisoryTriggerPort> advisoryTriggerPortProvider;

    private volatile RiskLevel highestRiskLevel = RiskLevel.SAFE;
    private final AtomicBoolean generatingFlag = new AtomicBoolean(false);

    public void onSceneUpdate(LlmRiskContext context) {
        if (!llmProperties.getAdvisory().isEnabled()) {
            return;
        }
        if (context == null || context.getTargets() == null) {
            return;
        }

        RiskLevel newHighest = maxRiskLevelOf(context.getTargets());
        boolean tcpaCrossed = anyApproachingBelowThreshold(
                context.getTargets(),
                llmProperties.getAdvisory().getTcpaThresholdSeconds()
        );

        boolean levelUpgraded = newHighest.compareTo(highestRiskLevel) > 0;
        highestRiskLevel = newHighest; // track current scene level, not historical max

        if ((levelUpgraded || tcpaCrossed) && generatingFlag.compareAndSet(false, true)) {
            try {
                var snapshot = agentSnapshotFactory.build();
                AdvisoryTriggerPort advisoryTriggerPort = advisoryTriggerPortProvider.getIfAvailable(
                        () -> (ignoredSnapshot, onComplete) -> onComplete.run()
                );
                advisoryTriggerPort.onAdvisoryTrigger(snapshot, () -> generatingFlag.set(false));
            } catch (Exception e) {
                generatingFlag.set(false);
                log.warn("Advisory trigger failed, generatingFlag reset: {}", e.getMessage());
            }
        }
    }

    public void clearGeneratingFlag() {
        generatingFlag.set(false);
    }

    private RiskLevel maxRiskLevelOf(List<LlmRiskTargetContext> targets) {
        return targets.stream()
                .map(LlmRiskTargetContext::getRiskLevel)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.SAFE);
    }

    private boolean anyApproachingBelowThreshold(List<LlmRiskTargetContext> targets, int thresholdSeconds) {
        return targets.stream()
                .anyMatch(t -> t.isApproaching() && t.getTcpaSec() < thresholdSeconds);
    }
}
