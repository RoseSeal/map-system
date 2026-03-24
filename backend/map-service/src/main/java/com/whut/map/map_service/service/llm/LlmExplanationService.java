package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskOwnShipContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.engine.risk.RiskConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExplanationService {
    private final LlmProperties llmProperties;
    private final LlmClient llmClient;

    public Map<String, LlmExplanation> generateTargetExplanations(
            LlmRiskOwnShipContext ownShip,
            List<LlmRiskTargetContext> triggeredTargets
    ) {

        // 如果没有风险状态目标船，直接返回空解释
        if (triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no risky targets require semantic explanation");
            return Collections.emptyMap();
        }

        log.info("LLM v1 scaffold triggered for ownShip={}, riskyTargets={}, model={}",
                ownShip == null ? null : ownShip.getId(),
                triggeredTargets.size(),
                llmProperties.getModel());

        Map<String, LlmExplanation> explanations = new LinkedHashMap<>();

        for(LlmRiskTargetContext target : triggeredTargets) {
            String prompt = buildPrompt(ownShip, target);
            log.debug("Built LLM prompt for target {}: {}", target.getTargetId(), prompt);
            try {
                // 使用CompletableFuture实现超时降级
                String text = CompletableFuture
                        .supplyAsync(() -> llmClient.generateText(prompt))
                        .get(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS);

                explanations.put(target.getTargetId(), LlmExplanation.builder()
                        .source(RiskConstants.EXPLANATION_SOURCE_LLM)
                        .text(text)
                        .build()
                );
            } catch (TimeoutException e) {
                log.warn("LLM client timeout for target {} after {} ms. Falling back to template explanation.",
                        target.getTargetId(), llmProperties.getTimeoutMs());
                if (llmProperties.isFallbackTemplateEnabled()) {
                    explanations.put(target.getTargetId(), LlmExplanation.builder()
                            .source(RiskConstants.EXPLANATION_SOURCE_FALLBACK)
                            .text(buildFallbackText(target))
                            .build());
                }
            } catch (Exception e) {
                log.warn("LLM client failed for target {}, error: {}. Falling back to template explanation.", target.getTargetId(), e.getMessage());
                if (llmProperties.isFallbackTemplateEnabled()) {
                    explanations.put(target.getTargetId(), LlmExplanation.builder()
                            .source(RiskConstants.EXPLANATION_SOURCE_FALLBACK)
                            .text(buildFallbackText(target))
                            .build());
                }
            }
        }
        // TODO(v1): Parse the raw model response into targetId -> explanation.text.
        // TODO(v1): Log raw response, parsed result, and final outward explanation under desensitization rules.

        return explanations;
    }

    private String buildPrompt(LlmRiskOwnShipContext ownShip, LlmRiskTargetContext target) {
        return """
            你是一名航行安全助手，请根据以下态势信息，用1-2句简洁中文描述当前风险并给出建议。
            
            【本船】
            ID: %s
            位置: (%.4f, %.4f)
            航速: %.1f 节，航向: %.1f°
            
            【目标船】
            ID: %s
            位置: (%.4f, %.4f)
            航速: %.1f 节，航向: %.1f°
            风险等级: %s
            DCPA: %.2f 海里，TCPA: %.0f 秒
            接近中: %s
            规则说明: %s
            
            请输出风险描述。
            """.formatted(
                ownShip.getId(),
                ownShip.getLongitude(), ownShip.getLatitude(),
                ownShip.getSog(), ownShip.getCog(),
                target.getTargetId(),
                target.getLongitude(), target.getLatitude(),
                target.getSpeedKn(), target.getCourseDeg(),
                target.getRiskLevel(),
                target.getDcpaNm(), target.getTcpaSec(),
                target.isApproaching() ? "是" : "否",
                target.getRuleExplanation() != null ? target.getRuleExplanation() : "无"
        );
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
