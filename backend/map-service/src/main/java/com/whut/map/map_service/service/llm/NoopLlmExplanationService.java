package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.engine.risk.RiskConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class NoopLlmExplanationService implements LlmExplanationService {
    private final LlmProperties llmProperties;

    public NoopLlmExplanationService(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
    }

    @Override
    public Map<String, LlmExplanation> generateTargetExplanations(LlmRiskContext context) {
        if (!llmProperties.isEnabled()) {
            log.debug("Skipping LLM explanation because llm.enabled=false");
            return Collections.emptyMap();
        }

        if (context == null || context.getTargets() == null || context.getTargets().isEmpty()) {
            log.debug("Skipping LLM explanation because no target context is available");
            return Collections.emptyMap();
        }

        List<LlmRiskTargetContext> triggeredTargets = context.getTargets().stream()
                .filter(this::shouldExplainTarget)
                .limit(llmProperties.getMaxTargetsPerCall())
                .toList();

        if (triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no risky targets require semantic explanation");
            return Collections.emptyMap();
        }

        log.info("LLM v1 scaffold triggered for ownShip={}, riskyTargets={}, model={}",
                context.getOwnShip() == null ? null : context.getOwnShip().getId(),
                triggeredTargets.size(),
                llmProperties.getModel());

        // TODO(v1): Build the real LLM prompt from ownShip + triggeredTargets.
        // TODO(v1): Invoke the concrete LLM client with llm.model and llm.timeout-ms.
        // TODO(v1): Parse the raw model response into targetId -> explanation.text.
        // TODO(v1): Log raw response, parsed result, and final outward explanation under desensitization rules.
        // TODO(v1): Replace the fallback template below with the real model output once the client is wired.
        if (llmProperties.isFallbackTemplateEnabled()) {
            return buildFallbackExplanations(triggeredTargets);
        }
        return Collections.emptyMap();
    }

    private boolean shouldExplainTarget(LlmRiskTargetContext target) {
        return target != null
                && target.getRiskLevel() != null
                && !RiskConstants.SAFE.equals(target.getRiskLevel());
    }

    private Map<String, LlmExplanation> buildFallbackExplanations(List<LlmRiskTargetContext> targets) {
        Map<String, LlmExplanation> explanations = new LinkedHashMap<>();
        for (LlmRiskTargetContext target : targets) {
            explanations.put(target.getTargetId(), LlmExplanation.builder()
                    .source(RiskConstants.EXPLANATION_SOURCE_FALLBACK)
                    .text(buildFallbackText(target))
                    .build());
        }
        return explanations;
    }

    private String buildFallbackText(LlmRiskTargetContext target) {
        String confidenceSuffix = target.getConfidence() == null
                ? ""
                : String.format(" Data confidence %.2f.", target.getConfidence());
        return String.format(
                "Target %s is assessed as %s with DCPA %.2f nm and TCPA %.0f sec.%s",
                target.getTargetId(),
                target.getRiskLevel(),
                target.getDcpaNm(),
                target.getTcpaSec(),
                confidenceSuffix
        );
    }
}
