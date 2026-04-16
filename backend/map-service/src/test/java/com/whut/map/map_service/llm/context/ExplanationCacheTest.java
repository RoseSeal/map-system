package com.whut.map.map_service.llm.context;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationCacheTest {

    @Test
    void refreshTargetStateEvictsMissingAndSafeTargets() {
        ExplanationCache cache = new ExplanationCache();
        cache.refreshTargetState(Set.of("target-1", "target-2"), Set.of("target-1", "target-2"));
        cache.put("target-1", "warning text", "2026-04-16T10:00:00Z");
        cache.put("target-2", "another text", "2026-04-16T10:01:00Z");

        cache.refreshTargetState(Set.of("target-1", "target-3"), Set.of("target-3"));

        assertThat(cache.getText("target-1")).isNull();
        assertThat(cache.getText("target-2")).isNull();
        assertThat(cache.shouldAccept("target-1")).isFalse();
        assertThat(cache.shouldAccept("target-2")).isFalse();
        assertThat(cache.shouldAccept("target-3")).isTrue();
    }

    @Test
    void putStoresOnlyTrackedNonSafeTargets() {
        ExplanationCache cache = new ExplanationCache();
        cache.refreshTargetState(Set.of("target-1", "target-2"), Set.of("target-1"));

        cache.put("target-1", "accepted", "2026-04-16T10:05:00Z");
        cache.put("target-2", "rejected", "2026-04-16T10:06:00Z");
        cache.put("missing", "rejected", "2026-04-16T10:07:00Z");

        assertThat(cache.getText("target-1")).isEqualTo("accepted");
        assertThat(cache.getTimestamp("target-1")).isEqualTo("2026-04-16T10:05:00Z");
        assertThat(cache.getText("target-2")).isNull();
        assertThat(cache.getText("missing")).isNull();
    }
}
