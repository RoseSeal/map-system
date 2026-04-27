package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.ExplanationPayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationCacheTest {

    private static ExplanationPayload payload(String eventId, String targetId, String riskLevel, String text) {
        return ExplanationPayload.builder()
                .eventId(eventId)
                .targetId(targetId)
                .riskLevel(riskLevel)
                .provider("gemini")
                .text(text)
                .timestamp("2026-04-27T10:00:00Z")
                .build();
    }

    @Test
    void activeExplanationRetainedWhileTargetRemainsNonSafe() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.putActive(payload("e1", "t1", "WARNING", "warning text"));

        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t.plusSeconds(5));

        assertThat(cache.getText("t1")).isEqualTo("warning text");
        assertThat(cache.resolvedSize()).isZero();
    }

    @Test
    void activeExplanationMigratedToResolvedWhenTargetBecomesSafe() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.putActive(payload("e1", "t1", "WARNING", "warning text"));

        cache.refreshTargetState(Set.of("t1"), Set.of(), Map.of("t1", RiskLevel.SAFE), t.plusSeconds(10));

        assertThat(cache.getText("t1")).isNull();
        assertThat(cache.shouldAccept("t1")).isFalse();
        assertThat(cache.resolvedSize()).isEqualTo(1);

        Optional<ExplanationCache.CachedExplanationEntry> found = cache.findForChatContext("t1", "e1");
        assertThat(found).isPresent();
        assertThat(found.get().resolvedReason()).isEqualTo(ExplanationResolutionReason.TARGET_SAFE);
        assertThat(found.get().status()).isEqualTo(ExplanationLifecycleStatus.RESOLVED);
        assertThat(found.get().text()).isEqualTo("warning text");
    }

    @Test
    void activeExplanationMigratedToResolvedWhenTargetDisappears() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.putActive(payload("e1", "t1", "WARNING", "alarm text"));

        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), t.plusSeconds(10));

        assertThat(cache.getText("t1")).isNull();
        assertThat(cache.resolvedSize()).isEqualTo(1);

        Optional<ExplanationCache.CachedExplanationEntry> found = cache.findForChatContext("t1", "e1");
        assertThat(found).isPresent();
        assertThat(found.get().resolvedReason()).isEqualTo(ExplanationResolutionReason.TARGET_MISSING);
    }

    @Test
    void resolvedEntriesExpiredAfterTtl() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.putActive(payload("e1", "t1", "WARNING", "text"));

        Instant resolvedAt = t.plusSeconds(10);
        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), resolvedAt);
        assertThat(cache.resolvedSize()).isEqualTo(1);

        Instant afterTtl = resolvedAt.plusSeconds(ExplanationCache.RESOLVED_TTL_MINUTES * 60 + 1);
        List<String> removed = cache.clearExpiredResolvedExplanations(afterTtl);
        assertThat(removed).containsExactly("e1");
        assertThat(cache.resolvedSize()).isZero();
    }

    @Test
    void resolvedEntriesCapAtMaxAndOldestEvictedFirst() {
        ExplanationCache cache = new ExplanationCache();
        Instant base = Instant.parse("2026-04-27T10:00:00Z");
        int limit = ExplanationCache.RESOLVED_MAX_ENTRIES;

        for (int i = 0; i < limit + 2; i++) {
            String targetId = "t" + i;
            String eventId = "e" + i;
            cache.refreshTargetState(Set.of(targetId), Set.of(targetId), Map.of(targetId, RiskLevel.WARNING), base.plusSeconds(i));
            cache.putActive(payload(eventId, targetId, "WARNING", "text " + i));
            cache.refreshTargetState(Set.of(), Set.of(), Map.of(), base.plusSeconds(i + 1));
        }

        assertThat(cache.resolvedSize()).isEqualTo(limit);
        assertThat(cache.findForChatContext("t0", "e0")).isEmpty();
        assertThat(cache.findForChatContext("t1", "e1")).isEmpty();
        assertThat(cache.findForChatContext("t" + limit, "e" + limit)).isPresent();
        assertThat(cache.findForChatContext("t" + (limit + 1), "e" + (limit + 1))).isPresent();
    }

    @Test
    void clearExpiredOnlyRemovesTtlExpiredEntries() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");

        cache.refreshTargetState(Set.of("t1", "t2"), Set.of("t1", "t2"),
                Map.of("t1", RiskLevel.WARNING, "t2", RiskLevel.ALARM), t);
        cache.putActive(payload("e1", "t1", "WARNING", "early text"));
        cache.putActive(payload("e2", "t2", "ALARM", "late text"));

        Instant earlyResolvedAt = t.plusSeconds(10);
        cache.refreshTargetState(Set.of("t2"), Set.of("t2"),
                Map.of("t2", RiskLevel.ALARM), earlyResolvedAt);

        Instant lateResolvedAt = t.plusSeconds(20);
        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), lateResolvedAt);

        Instant cutoff = earlyResolvedAt.plusSeconds(ExplanationCache.RESOLVED_TTL_MINUTES * 60 + 1);
        List<String> removed = cache.clearExpiredResolvedExplanations(cutoff);

        assertThat(removed).containsExactly("e1");
        assertThat(cache.resolvedSize()).isEqualTo(1);
        assertThat(cache.findForChatContext("t2", "e2")).isPresent();
    }

    @Test
    void shouldAcceptReturnsFalseForResolvedTarget() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        assertThat(cache.shouldAccept("t1")).isTrue();

        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), t.plusSeconds(5));
        assertThat(cache.shouldAccept("t1")).isFalse();
    }

    @Test
    void putActiveRejectsWhenTrackedStateChangedBeforeWrite() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);

        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), t.plusSeconds(5));
        cache.putActive(payload("e1", "t1", "WARNING", "late write"));

        assertThat(cache.getText("t1")).isNull();
        assertThat(cache.resolvedSize()).isZero();
    }

    @Test
    void lateExplanationNotWrittenToActiveOrResolved() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.refreshTargetState(Set.of(), Set.of(), Map.of(), t.plusSeconds(5));

        assertThat(cache.shouldAccept("t1")).isFalse();
        cache.putActive(payload("e1", "t1", "WARNING", "late text"));

        assertThat(cache.getText("t1")).isNull();
        assertThat(cache.resolvedSize()).isZero();
        assertThat(cache.findForChatContext("t1", "e1")).isEmpty();
    }

    @Test
    void findForChatContextReturnsEmptyForActiveExplanationEventId() {
        ExplanationCache cache = new ExplanationCache();
        Instant t = Instant.parse("2026-04-27T10:00:00Z");
        cache.refreshTargetState(Set.of("t1"), Set.of("t1"), Map.of("t1", RiskLevel.WARNING), t);
        cache.putActive(payload("e1", "t1", "WARNING", "active text"));

        assertThat(cache.findForChatContext("t1", "e1")).isEmpty();
    }
}
