package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RiskContextHolder {

    private volatile Snapshot snapshot;

    public void update(long version, LlmRiskContext context) {
        this.snapshot = new Snapshot(version, context, Instant.now());
    }

    public LlmRiskContext getCurrent() {
        return snapshot == null ? null : snapshot.context();
    }

    public Instant getUpdatedAt() {
        return snapshot == null ? null : snapshot.updatedAt();
    }

    public Long getVersion() {
        return snapshot == null ? null : snapshot.version();
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public record Snapshot(long version, LlmRiskContext context, Instant updatedAt) {
    }
}
