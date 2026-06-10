package com.whut.map.map_service.llm.agent.casegraph;

public interface HistoricalCaseQueryPort {
    HistoricalCaseResult findSimilarCases(HistoricalCaseQuery query);
}
