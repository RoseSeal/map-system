package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskContext;

import java.util.Map;

public interface LlmExplanationService {
    Map<String, LlmExplanation> generateTargetExplanations(LlmRiskContext context);
}
