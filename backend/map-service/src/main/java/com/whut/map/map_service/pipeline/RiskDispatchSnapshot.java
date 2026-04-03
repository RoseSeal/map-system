package com.whut.map.map_service.pipeline;

import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import com.whut.map.map_service.engine.risk.RiskAssessmentResult;

record RiskDispatchSnapshot(
        RiskAssessmentResult riskAssessmentResult,
        RiskObjectDto riskObject,
        LlmRiskContext llmContext
) {
}
