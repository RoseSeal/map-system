package com.whut.map.map_service.llm.context;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class ExplanationCache {

    private record CachedEntry(String text, String timestamp) {
    }

    private final ConcurrentHashMap<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private final AtomicReference<Set<String>> trackedTargetIds = new AtomicReference<>(Set.of());
    private final AtomicReference<Set<String>> nonSafeTargetIds = new AtomicReference<>(Set.of());

    public void put(String targetId, String text, String timestamp) {
        if (!StringUtils.hasText(targetId) || !StringUtils.hasText(text)) {
            return;
        }

        Set<String> currentTracked = trackedTargetIds.get();
        Set<String> currentNonSafe = nonSafeTargetIds.get();
        if (!currentTracked.contains(targetId) || !currentNonSafe.contains(targetId)) {
            return;
        }

        cache.put(targetId, new CachedEntry(text, timestamp));
    }

    public String getText(String targetId) {
        CachedEntry entry = cache.get(targetId);
        return entry == null ? null : entry.text();
    }

    public String getTimestamp(String targetId) {
        CachedEntry entry = cache.get(targetId);
        return entry == null ? null : entry.timestamp();
    }

    public void refreshTargetState(Set<String> currentTargetIds, Set<String> currentNonSafeTargetIds) {
        Set<String> tracked = currentTargetIds == null ? Set.of() : Set.copyOf(currentTargetIds);
        Set<String> nonSafe = currentNonSafeTargetIds == null ? Set.of() : Set.copyOf(currentNonSafeTargetIds);
        trackedTargetIds.set(tracked);
        nonSafeTargetIds.set(nonSafe);
        cache.keySet().removeIf(targetId -> !tracked.contains(targetId) || !nonSafe.contains(targetId));
    }

    public boolean shouldAccept(String targetId) {
        if (!StringUtils.hasText(targetId)) {
            return false;
        }
        return trackedTargetIds.get().contains(targetId) && nonSafeTargetIds.get().contains(targetId);
    }
}
