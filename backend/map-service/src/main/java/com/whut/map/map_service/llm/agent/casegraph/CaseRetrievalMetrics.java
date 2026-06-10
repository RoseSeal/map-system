package com.whut.map.map_service.llm.agent.casegraph;

public record CaseRetrievalMetrics(
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens
) {}
