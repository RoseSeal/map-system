package com.whut.map.map_service.llm.agent.casegraph;

import java.util.List;

public record HistoricalCaseResult(
        CaseQueryMode mode,
        String queryEffective,
        List<HistoricalCase> cases,
        String answer,
        CaseRetrievalMetrics metrics
) {}
