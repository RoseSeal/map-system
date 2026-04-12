package com.whut.map.map_service.llm.context;

import com.whut.map.map_service.dto.sse.ExplanationPayload;
import com.whut.map.map_service.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.service.LlmErrorCode;
import com.whut.map.map_service.llm.service.LlmTriggerService;
import com.whut.map.map_service.transport.risk.RiskStreamPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmRiskEventListener {

    private final LlmRiskContextAssembler llmRiskContextAssembler;
    private final RiskContextHolder riskContextHolder;
    private final LlmTriggerService llmTriggerService;
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

        if (!event.triggerExplanations()) {
            return;
        }

        llmTriggerService.triggerExplanationsIfNeeded(
                context,
                explanation -> riskStreamPublisher.publishExplanation(buildExplanationPayload(explanation, event.riskObjectId())),
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
