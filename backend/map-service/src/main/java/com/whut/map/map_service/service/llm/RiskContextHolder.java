package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RiskContextHolder {

    private volatile Snapshot snapshot;

    public void update(LlmRiskContext context) {
        this.snapshot = new Snapshot(context, Instant.now());
    }

    public LlmRiskContext getCurrent() {
        return snapshot == null ? null : snapshot.context();
    }

    public Instant getUpdatedAt() {
        return snapshot == null ? null : snapshot.updatedAt();
    }

    record Snapshot(LlmRiskContext context, Instant updatedAt) {
    }
}
