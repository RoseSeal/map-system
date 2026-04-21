package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;

import java.util.Map;

public record AgentSnapshot(
        long snapshotVersion,
        LlmRiskContext riskContext,
        Map<String, TargetDerivedSnapshot> targetDetails
) {}
