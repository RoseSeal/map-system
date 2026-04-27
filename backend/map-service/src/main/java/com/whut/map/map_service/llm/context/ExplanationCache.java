package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.ExplanationPayload;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class ExplanationCache {

    static final int RESOLVED_MAX_ENTRIES = 20;
    static final long RESOLVED_TTL_MINUTES = 30L;

    record CachedExplanationEntry(
            String eventId,
            String targetId,
            String riskLevel,
            String provider,
            String text,
            String timestamp,
            ExplanationLifecycleStatus status,
            String resolvedAt,
            ExplanationResolutionReason resolvedReason
    ) {}

    private final ConcurrentHashMap<String, CachedExplanationEntry> activeCache = new ConcurrentHashMap<>();
    private final List<CachedExplanationEntry> resolvedList = new ArrayList<>();
    private final ReentrantLock resolvedLock = new ReentrantLock();

    private final AtomicReference<Set<String>> trackedTargetIds = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<String>> nonSafeTargetIds = new AtomicReference<>(Set.of());

    public void putActive(ExplanationPayload payload) {
        if (payload == null || !StringUtils.hasText(payload.getTargetId()) || !StringUtils.hasText(payload.getText())) {
            return;
        }
        if (!shouldAccept(payload.getTargetId())) {
            return;
        }
        activeCache.put(payload.getTargetId(), new CachedExplanationEntry(
                payload.getEventId(),
                payload.getTargetId(),
                payload.getRiskLevel(),
                payload.getProvider(),
                payload.getText(),
                payload.getTimestamp(),
                ExplanationLifecycleStatus.ACTIVE,
                null,
                null
        ));
    }

    public boolean shouldAccept(String targetId) {
        if (!StringUtils.hasText(targetId)) {
            return false;
        }
        return trackedTargetIds.get().contains(targetId) && nonSafeTargetIds.get().contains(targetId);
    }

    public String getText(String targetId) {
        CachedExplanationEntry entry = activeCache.get(targetId);
        return entry == null ? null : entry.text();
    }

    public String getTimestamp(String targetId) {
        CachedExplanationEntry entry = activeCache.get(targetId);
        return entry == null ? null : entry.timestamp();
    }

    public void refreshTargetState(
            Set<String> currentTargetIds,
            Set<String> currentNonSafeTargetIds,
            Map<String, RiskLevel> currentRiskLevels,
            Instant now
    ) {
        Set<String> tracked = currentTargetIds == null ? Set.of() : Set.copyOf(currentTargetIds);
        Set<String> nonSafe = currentNonSafeTargetIds == null ? Set.of() : Set.copyOf(currentNonSafeTargetIds);
        Map<String, RiskLevel> riskLevels = currentRiskLevels == null ? Map.of() : currentRiskLevels;

        trackedTargetIds.set(tracked);
        nonSafeTargetIds.set(nonSafe);

        String resolvedAtStr = now == null ? Instant.now().toString() : now.toString();

        List<CachedExplanationEntry> toMigrate = new ArrayList<>();
        activeCache.entrySet().removeIf(entry -> {
            String targetId = entry.getKey();
            if (nonSafe.contains(targetId)) {
                return false;
            }
            ExplanationResolutionReason reason = tracked.contains(targetId)
                    ? ExplanationResolutionReason.TARGET_SAFE
                    : ExplanationResolutionReason.TARGET_MISSING;
            CachedExplanationEntry active = entry.getValue();
            toMigrate.add(new CachedExplanationEntry(
                    active.eventId(),
                    active.targetId(),
                    active.riskLevel(),
                    active.provider(),
                    active.text(),
                    active.timestamp(),
                    ExplanationLifecycleStatus.RESOLVED,
                    resolvedAtStr,
                    reason
            ));
            return true;
        });

        if (!toMigrate.isEmpty()) {
            resolvedLock.lock();
            try {
                resolvedList.addAll(toMigrate);
                pruneResolved(now == null ? Instant.now() : now);
            } finally {
                resolvedLock.unlock();
            }
        }
    }

    public Optional<CachedExplanationEntry> findForChatContext(String targetId, String eventId) {
        if (!StringUtils.hasText(targetId) || !StringUtils.hasText(eventId)) {
            return Optional.empty();
        }
        resolvedLock.lock();
        try {
            return resolvedList.stream()
                    .filter(e -> targetId.equals(e.targetId()) && eventId.equals(e.eventId()))
                    .findFirst();
        } finally {
            resolvedLock.unlock();
        }
    }

    public List<String> clearExpiredResolvedExplanations(Instant now) {
        Instant effectiveNow = now == null ? Instant.now() : now;
        resolvedLock.lock();
        try {
            long ttlMs = RESOLVED_TTL_MINUTES * 60 * 1000L;
            List<String> removedEventIds = new ArrayList<>();
            resolvedList.removeIf(entry -> {
                if (!StringUtils.hasText(entry.resolvedAt())) {
                    return false;
                }
                try {
                    Instant resolvedAt = Instant.parse(entry.resolvedAt());
                    if (effectiveNow.toEpochMilli() - resolvedAt.toEpochMilli() > ttlMs) {
                        removedEventIds.add(entry.eventId());
                        return true;
                    }
                } catch (Exception ignored) {
                    // unparseable resolvedAt: keep entry
                }
                return false;
            });
            return removedEventIds;
        } finally {
            resolvedLock.unlock();
        }
    }

    // Called under resolvedLock
    private void pruneResolved(Instant now) {
        long ttlMs = RESOLVED_TTL_MINUTES * 60 * 1000L;
        // Step 1: evict TTL-expired
        resolvedList.removeIf(entry -> {
            if (!StringUtils.hasText(entry.resolvedAt())) {
                return false;
            }
            try {
                Instant resolvedAt = Instant.parse(entry.resolvedAt());
                return now.toEpochMilli() - resolvedAt.toEpochMilli() > ttlMs;
            } catch (Exception ignored) {
                return false;
            }
        });
        // Step 2: evict oldest if over limit
        if (resolvedList.size() > RESOLVED_MAX_ENTRIES) {
            resolvedList.sort(Comparator
                    .comparing(CachedExplanationEntry::resolvedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(CachedExplanationEntry::eventId, Comparator.nullsFirst(Comparator.naturalOrder())));
            resolvedList.subList(0, resolvedList.size() - RESOLVED_MAX_ENTRIES).clear();
        }
    }

    int resolvedSize() {
        resolvedLock.lock();
        try {
            return resolvedList.size();
        } finally {
            resolvedLock.unlock();
        }
    }
}
