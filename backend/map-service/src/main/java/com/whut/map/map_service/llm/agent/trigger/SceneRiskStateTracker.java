package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.shared.domain.RiskLevel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
public class SceneRiskStateTracker {

    private final LlmProperties llmProperties;
    private final AgentSnapshotFactory agentSnapshotFactory;
    private final ObjectProvider<AdvisoryTriggerPort> advisoryTriggerPortProvider;
    private final Clock clock;

    private RiskLevel highestRiskLevel = RiskLevel.SAFE;
    private final AtomicBoolean generatingFlag = new AtomicBoolean(false);
    private final Map<String, Encounter> encounters = new HashMap<>();

    /** Per-target encounter state; guarded by this tracker's monitor. */
    private static final class Encounter {
        RiskLevel currentLevel = RiskLevel.SAFE;
        RiskLevel attempted;
        RiskLevel covered;
        Long safeSince;
        long retryAt;
        long retryDelay;
        boolean dangerous;
    }

    private record Attempt(Encounter encounter, RiskLevel level) {
    }

    @Autowired
    public SceneRiskStateTracker(
            LlmProperties llmProperties,
            AgentSnapshotFactory agentSnapshotFactory,
            ObjectProvider<AdvisoryTriggerPort> advisoryTriggerPortProvider
    ) {
        this(llmProperties, agentSnapshotFactory, advisoryTriggerPortProvider, Clock.systemUTC());
    }

    SceneRiskStateTracker(
            LlmProperties llmProperties,
            AgentSnapshotFactory agentSnapshotFactory,
            ObjectProvider<AdvisoryTriggerPort> advisoryTriggerPortProvider,
            Clock clock
    ) {
        this.llmProperties = llmProperties;
        this.agentSnapshotFactory = agentSnapshotFactory;
        this.advisoryTriggerPortProvider = advisoryTriggerPortProvider;
        this.clock = clock;
    }

    public synchronized void onSceneUpdate(LlmRiskContext context) {
        if (!llmProperties.getAdvisory().isEnabled()) {
            return;
        }
        if (context == null || context.getTargets() == null) {
            return;
        }
        if (!llmProperties.getAdvisory().isEncounterDedupEnabled()) {
            legacyUpdate(context);
            return;
        }

        long now = clock.millis();
        updateEncounters(context, now);
        if (generatingFlag.get() || encounters.values().stream().noneMatch(state -> isEligible(state, now))) {
            return;
        }

        Map<String, Attempt> attempts = new HashMap<>();
        try {
            var snapshot = agentSnapshotFactory.build();
            if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
                return;
            }
            for (var target : snapshot.riskContext().getTargets()) {
                Encounter state = encounters.get(target.getTargetId());
                if (state == null || !state.dangerous || !isDangerous(target)) {
                    continue;
                }
                // Credit only the level actually present in the frozen generation snapshot.
                attempts.put(target.getTargetId(), new Attempt(state, levelOf(target)));
            }
            if (attempts.isEmpty()) {
                return;
            }

            generatingFlag.set(true);
            for (var attempt : attempts.values()) {
                Encounter state = attempt.encounter();
                if (state.attempted == null || attempt.level().compareTo(state.attempted) > 0) {
                    state.attempted = attempt.level();
                    state.retryDelay = 0;
                }
            }
            log.info("Advisory triggered: dedup=true targets={} snapshot={}",
                    attempts.keySet(), snapshot.snapshotVersion());

            AdvisoryTriggerPort advisoryTriggerPort = advisoryTriggerPortProvider.getIfAvailable();
            if (advisoryTriggerPort == null) {
                complete(attempts, Set.of());
            } else {
                advisoryTriggerPort.onAdvisoryTrigger(snapshot, covered -> complete(attempts, covered));
            }
        } catch (Exception e) {
            // Snapshot construction failures also back off instead of retrying every event.
            if (attempts.isEmpty()) {
                encounters.forEach((targetId, state) -> {
                    if (isEligible(state, now)) {
                        state.attempted = state.currentLevel;
                        attempts.put(targetId, new Attempt(state, state.currentLevel));
                    }
                });
            }
            complete(attempts, Set.of());
            log.warn("Advisory trigger failed: {}", e.getMessage());
        }
    }

    public void clearGeneratingFlag() {
        generatingFlag.set(false);
    }

    private void updateEncounters(LlmRiskContext context, long now) {
        Set<String> presentTargetIds = new HashSet<>();
        for (var target : context.getTargets()) {
            String targetId = target.getTargetId();
            if (targetId == null) {
                continue;
            }
            presentTargetIds.add(targetId);
            Encounter state = encounters.get(targetId);
            if (isDangerous(target)) {
                if (state == null || (state.safeSince != null && isCleared(state, now))) {
                    state = new Encounter();
                    encounters.put(targetId, state);
                }
                state.dangerous = true;
                state.safeSince = null;
                state.currentLevel = levelOf(target);
            } else if (state != null) {
                state.dangerous = false;
                if (state.safeSince == null) {
                    state.safeSince = now;
                }
                if (isCleared(state, now)) {
                    encounters.remove(targetId);
                }
            }
        }
        // Context contains all tracked targets. Absence follows the existing ship expiry lifecycle.
        encounters.keySet().retainAll(presentTargetIds);
    }

    private synchronized void complete(Map<String, Attempt> attempts, Set<String> coveredTargetIds) {
        long now = clock.millis();
        attempts.forEach((targetId, attempt) -> {
            Encounter state = attempt.encounter();
            // A late callback must not modify a new encounter with the same target ID.
            if (encounters.get(targetId) != state) {
                return;
            }
            if (coveredTargetIds != null && coveredTargetIds.contains(targetId)) {
                if (state.covered == null || attempt.level().compareTo(state.covered) > 0) {
                    state.covered = attempt.level();
                }
                state.retryDelay = 0;
            } else if (state.covered == null || attempt.level().compareTo(state.covered) > 0) {
                scheduleRetry(targetId, state, now);
            }
        });
        generatingFlag.set(false);
    }

    private void scheduleRetry(String targetId, Encounter state, long now) {
        long initialDelay = Math.max(1, llmProperties.getAdvisory().getRetryInitialSeconds()) * 1000L;
        long maxDelay = Math.max(1, llmProperties.getAdvisory().getRetryMaxSeconds()) * 1000L;
        state.retryDelay = Math.min(maxDelay, state.retryDelay == 0 ? initialDelay : state.retryDelay * 2);
        state.retryAt = now + state.retryDelay;
        log.info("Advisory retry deferred: target={} delayMs={}", targetId, state.retryDelay);
    }

    private boolean isCleared(Encounter state, long now) {
        return now - state.safeSince >= llmProperties.getAdvisory().getEncounterClearSeconds() * 1000L;
    }

    private boolean isEligible(Encounter state, long now) {
        if (!state.dangerous) {
            return false;
        }
        if (state.attempted == null || state.currentLevel.compareTo(state.attempted) > 0) {
            return true;
        }
        boolean notCoveredAtCurrentLevel = state.covered == null || state.currentLevel.compareTo(state.covered) > 0;
        return notCoveredAtCurrentLevel && now >= state.retryAt;
    }

    private boolean isDangerous(LlmRiskTargetContext target) {
        return levelOf(target).compareTo(RiskLevel.WARNING) >= 0
                || (target.isApproaching()
                && target.getTcpaSec() < llmProperties.getAdvisory().getTcpaThresholdSeconds());
    }

    private RiskLevel levelOf(LlmRiskTargetContext target) {
        return target.getRiskLevel() == null ? RiskLevel.SAFE : target.getRiskLevel();
    }

    /** Pre-dedup trigger: scene-level upgrade or any approaching target below the TCPA threshold. */
    private void legacyUpdate(LlmRiskContext context) {
        RiskLevel newHighest = context.getTargets().stream()
                .map(LlmRiskTargetContext::getRiskLevel)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.SAFE);
        boolean tcpaCrossed = context.getTargets().stream()
                .anyMatch(t -> t.isApproaching()
                        && t.getTcpaSec() < llmProperties.getAdvisory().getTcpaThresholdSeconds());

        boolean levelUpgraded = newHighest.compareTo(highestRiskLevel) > 0;
        highestRiskLevel = newHighest; // track current scene level, not historical max

        if ((levelUpgraded || tcpaCrossed) && generatingFlag.compareAndSet(false, true)) {
            try {
                var snapshot = agentSnapshotFactory.build();
                AdvisoryTriggerPort advisoryTriggerPort = advisoryTriggerPortProvider.getIfAvailable(
                        () -> (ignoredSnapshot, onComplete) -> onComplete.accept(Set.of())
                );
                log.info("Advisory triggered: dedup=false snapshot={}", snapshot.snapshotVersion());
                advisoryTriggerPort.onAdvisoryTrigger(snapshot, ignored -> generatingFlag.set(false));
            } catch (Exception e) {
                generatingFlag.set(false);
                log.warn("Advisory trigger failed, generatingFlag reset: {}", e.getMessage());
            }
        }
    }
}
