package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;
import com.whut.map.map_service.llm.dto.LlmRiskContext;

record RiskDispatchSnapshot(
        RiskAssessmentResult riskAssessmentResult,
        RiskObjectDto riskObject,
        LlmRiskContext llmContext
) {
}
