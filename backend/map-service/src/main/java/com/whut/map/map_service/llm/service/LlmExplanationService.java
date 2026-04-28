package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmExecutorConfig;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.client.LlmClientRegistry;
import com.whut.map.map_service.llm.client.LlmProvider;
import com.whut.map.map_service.llm.client.LlmProviderSelectionStore;
import com.whut.map.map_service.llm.client.LlmTaskType;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.dto.LlmExplanation;
import com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.util.GeoUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExplanationService {
    private final LlmProperties llmProperties;
    private final LlmClientRegistry llmClientRegistry;
    private final LlmProviderSelectionStore llmProviderSelectionStore;
    private final PromptTemplateService promptTemplateService;
    @Qualifier(LlmExecutorConfig.LLM_EXECUTOR)
    private final ExecutorService llmExecutor;

    public record LlmExplanationError(LlmErrorCode errorCode, String errorMessage) {
    }

    public enum TriggerReason {
        LEVEL_UPGRADE,
        COOLDOWN_REFRESH
    }

    public record ExplanationTrigger(
            LlmRiskTargetContext target,
            TriggerReason triggerReason,
            RiskLevel previousRiskLevel
    ) {
    }

    public void generateTargetExplanationsAsync(
            LlmRiskOwnShipContext ownShip,
            List<ExplanationTrigger> triggeredTargets,
            Consumer<LlmExplanation> onSuccess,
            BiConsumer<LlmRiskTargetContext, LlmExplanationError> onError
    ) {

        if (triggeredTargets == null || triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no risky targets require semantic explanation");
            return;
        }

        LlmProvider configuredProvider = llmProviderSelectionStore.getSelection(LlmTaskType.EXPLANATION);
        LlmProvider provider;
        LlmClient llmClient;
        try {
            provider = llmClientRegistry.resolveProviderForTask(LlmTaskType.EXPLANATION);
            llmClient = llmClientRegistry.find(provider)
                    .orElseThrow(() -> new IllegalStateException("provider unavailable: " + provider));
        } catch (Exception e) {
            log.warn("No provider available for explanation batch: {}", e.getMessage());
            for (ExplanationTrigger trigger : triggeredTargets) {
                onError.accept(trigger.target(), new LlmExplanationError(
                        LlmErrorCode.LLM_DISABLED,
                        "LLM explanation provider is unavailable."
                ));
            }
            return;
        }
        String providerName = provider.getValue();
        log.info("LLM explanation triggered asynchronously for ownShip={}, riskyTargets={}, provider={}",
                ownShip.getId(),
                triggeredTargets.size(),
                configuredProvider);

        for (ExplanationTrigger trigger : triggeredTargets) {
            LlmRiskTargetContext target = trigger.target();
            List<LlmChatMessage> messages = buildMessages(ownShip, trigger);
            log.debug("Built LLM messages for target {}: {}", target.getTargetId(), messages);
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> llmClient.chat(messages), llmExecutor);
            future
                    .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .whenComplete((text, throwable) -> {
                        if (throwable == null) {
                            log.debug("LLM response for target {}: {}", target.getTargetId(), text);
                            onSuccess.accept(LlmExplanation.builder()
                                    .source(LlmExplanation.SOURCE_LLM)
                                    .provider(providerName)
                                    .targetId(target.getTargetId())
                                    .riskLevel(target.getRiskLevel() == null ? null : target.getRiskLevel().name())
                                    .text(text)
                                    .timestamp(Instant.now().toString())
                                    .build());
                            return;
                        }

                        Throwable cause = unwrap(throwable);
                        if (cause instanceof TimeoutException) {
                            future.cancel(true);
                            log.warn("LLM call timed out for target {} after {} ms. Check DNS/proxy/network reachability.",
                                    target.getTargetId(), llmProperties.getTimeoutMs());
                            onError.accept(target, new LlmExplanationError(
                                    LlmErrorCode.LLM_TIMEOUT,
                                    "LLM explanation request timed out."
                            ));
                            return;
                        }

                        log.warn("LLM client failed for target {}, type={}, error={}",
                                target.getTargetId(), cause.getClass().getSimpleName(), cause.getMessage());
                        onError.accept(target, new LlmExplanationError(
                                LlmErrorCode.LLM_FAILED,
                                "LLM explanation request failed."
                        ));
                    });
        }
    }

    private List<LlmChatMessage> buildMessages(LlmRiskOwnShipContext ownShip, ExplanationTrigger trigger) {
        LlmRiskTargetContext target = trigger.target();
        return List.of(
                new LlmChatMessage(
                        ChatRole.SYSTEM,
                        promptTemplateService.getSystemPrompt(PromptScene.RISK_EXPLANATION)
                ),
                new LlmChatMessage(
                        ChatRole.USER,
                        promptTemplateService.getSystemPrompt(PromptScene.RISK_EXPLANATION_USER_CONTEXT).formatted(
                                ownShip.getId(),
                                ownShip.getLongitude(), ownShip.getLatitude(),
                                ownShip.getSog(), formatOwnShipHeading(ownShip),
                                target.getTargetId(),
                                target.getLongitude(), target.getLatitude(),
                                target.getSpeedKn(), target.getCourseDeg(),
                                formatRiskLevel(target.getRiskLevel()),
                                formatTriggerReason(trigger),
                                formatDistanceNm(target.getCurrentDistanceNm()),
                                formatRelativeBearing(target.getRelativeBearingDeg()),
                                target.getDcpaNm(), target.getTcpaSec(),
                                target.isApproaching() ? "是" : "否",
                                formatRuleExplanation(target.getRuleExplanation())
                        )
                )
        );
    }

    private String formatTriggerReason(ExplanationTrigger trigger) {
        if (trigger.triggerReason() == TriggerReason.LEVEL_UPGRADE) {
            String previous = formatRiskLevel(trigger.previousRiskLevel());
            String current = formatRiskLevel(trigger.target().getRiskLevel());
            return "风险等级升级（%s -> %s）".formatted(previous, current);
        }
        return "冷却窗口到期，基于当前态势重新解释";
    }

    private String formatRiskLevel(RiskLevel riskLevel) {
        if (riskLevel == null) {
            return "未知";
        }
        return switch (riskLevel) {
            case ALARM -> "警报";
            case WARNING -> "预警";
            case CAUTION -> "注意";
            case SAFE -> "安全";
        };
    }

    private String formatRuleExplanation(String ruleExplanation) {
        if (!StringUtils.hasText(ruleExplanation)) {
            return "无";
        }
        return ruleExplanation
                .replace("ALARM", "警报")
                .replace("WARNING", "预警")
                .replace("CAUTION", "注意")
                .replace("SAFE", "安全");
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private String formatDistanceNm(Double currentDistanceNm) {
        return currentDistanceNm == null ? "未知" : String.format("%.2f 海里", currentDistanceNm);
    }

    private String formatOwnShipHeading(LlmRiskOwnShipContext ownShip) {
        Double heading = ownShip.getHeading();
        if (heading != null) {
            return String.format("%.1f°", heading);
        }
        return String.format("%.1f° (COG近似)", ownShip.getCog());
    }

    private String formatRelativeBearing(Double relativeBearingDeg) {
        if (relativeBearingDeg == null) {
            return "未知";
        }
        return "%s (%.0f°)".formatted(GeoUtils.bearingSectorLabel(relativeBearingDeg), relativeBearingDeg);
    }
}
