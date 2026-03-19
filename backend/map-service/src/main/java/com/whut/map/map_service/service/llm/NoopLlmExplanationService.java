package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class NoopLlmExplanationService implements LlmExplanationService {
    @Override
    public Map<String, LlmExplanation> generateTargetExplanations(LlmRiskContext context) {
        return Collections.emptyMap();
    }
}
