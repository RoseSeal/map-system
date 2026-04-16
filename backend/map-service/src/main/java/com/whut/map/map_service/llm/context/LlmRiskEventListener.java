package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.shared.dto.sse.ExplanationPayload;
import com.whut.map.map_service.risk.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.service.LlmErrorCode;
import com.whut.map.map_service.llm.service.LlmTriggerService;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRiskEventListener {

    private final LlmRiskContextAssembler llmRiskContextAssembler;
    private final RiskContextHolder riskContextHolder;
    private final LlmTriggerService llmTriggerService;
    private final ExplanationCache explanationCache;
    private final RiskStreamPublisher riskStreamPublisher;

    @EventListener
    public void onRiskAssessmentCompleted(RiskAssessmentCompletedEvent event) {
        if (event == null) {
            return;
        }

        var context = llmRiskContextAssembler.assemble(
                event.ownShip(),
                event.allShips(),
                event.cpaResults(),
                event.riskResult()
        );
        riskContextHolder.update(event.snapshotVersion(), context);
        explanationCache.refreshTargetState(buildCurrentTargetIds(event), buildCurrentNonSafeTargetIds(context));

        if (!event.triggerExplanations()) {
            return;
        }

        llmTriggerService.triggerExplanationsIfNeeded(
                context,
                explanation -> {
                    ExplanationPayload payload = buildExplanationPayload(explanation, event.riskObjectId());
                    if (!explanationCache.shouldAccept(explanation.getTargetId())) {
                        log.debug("Drop stale explanation for targetId={}", explanation.getTargetId());
                        return;
                    }
                    explanationCache.put(explanation.getTargetId(), explanation.getText(), payload.getTimestamp());
                    riskStreamPublisher.publishExplanation(payload);
                },
                (target, error) -> riskStreamPublisher.publishError(
                        mapExplanationErrorCode(error == null ? null : error.errorCode()),
                        error == null ? "LLM explanation request failed." : error.errorMessage(),
                        target == null ? null : target.getTargetId()
                )
        );
    }

    private ExplanationPayload buildExplanationPayload(LlmExplanation explanation, String riskObjectId) {
        return ExplanationPayload.builder()
                .eventId(UUID.randomUUID().toString())
                .riskObjectId(riskObjectId)
                .targetId(explanation.getTargetId())
                .riskLevel(explanation.getRiskLevel())
                .provider(explanation.getProvider() != null ? explanation.getProvider() : explanation.getSource())
                .text(explanation.getText())
                .timestamp(explanation.getTimestamp() != null ? explanation.getTimestamp() : Instant.now().toString())
                .build();
    }

    private Set<String> buildCurrentTargetIds(RiskAssessmentCompletedEvent event) {
        if (event.allShips() == null || event.ownShip() == null || !StringUtils.hasText(event.ownShip().getId())) {
            return Set.of();
        }
        String ownId = event.ownShip().getId();
        return event.allShips().stream()
                .filter(ship -> ship != null && StringUtils.hasText(ship.getId()) && !ship.getId().equals(ownId))
                .map(ship -> ship.getId())
                .collect(Collectors.toSet());
    }

    private Set<String> buildCurrentNonSafeTargetIds(com.whut.map.map_service.llm.dto.LlmRiskContext context) {
        if (context == null || context.getTargets() == null) {
            return Set.of();
        }
        return context.getTargets().stream()
                .filter(target -> target != null
                        && target.getRiskLevel() != null
                        && target.getRiskLevel() != com.whut.map.map_service.shared.domain.RiskLevel.SAFE
                        && StringUtils.hasText(target.getTargetId()))
                .map(target -> target.getTargetId())
                .collect(Collectors.toSet());
    }

    private String mapExplanationErrorCode(LlmErrorCode errorCode) {
        if (errorCode == null) {
            return "LLM_REQUEST_FAILED";
        }
        return switch (errorCode) {
            case LLM_TIMEOUT -> "LLM_TIMEOUT";
            case LLM_FAILED -> "LLM_REQUEST_FAILED";
            case LLM_DISABLED, CONVERSATION_BUSY, TRANSCRIPTION_FAILED, TRANSCRIPTION_TIMEOUT -> {
                log.debug("Using generic failure mapping for non-explanation LLM error code {}", errorCode);
                yield "LLM_REQUEST_FAILED";
            }
        };
    }
}
