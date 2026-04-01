package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.llm.LlmExplanation;
import com.whut.map.map_service.dto.llm.LlmRiskOwnShipContext;
import com.whut.map.map_service.dto.llm.LlmRiskTargetContext;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.engine.risk.RiskConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmExplanationService {
    private final LlmProperties llmProperties;
    private final LlmClient llmClient;
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public record LlmExplanationError(ChatErrorCode errorCode, String errorMessage) {
    }

    public void generateTargetExplanationsAsync(
            LlmRiskOwnShipContext ownShip,
            List<LlmRiskTargetContext> triggeredTargets,
            Consumer<LlmExplanation> onSuccess,
            BiConsumer<LlmRiskTargetContext, LlmExplanationError> onError
    ) {

        if (triggeredTargets == null || triggeredTargets.isEmpty()) {
            log.debug("Skipping LLM explanation because no risky targets require semantic explanation");
            return;
        }

        log.info("LLM explanation triggered asynchronously for ownShip={}, riskyTargets={}, provider={}",
                ownShip.getId(),
                triggeredTargets.size(),
                llmProperties.getProvider());

        for (LlmRiskTargetContext target : triggeredTargets) {
            String prompt = buildPrompt(ownShip, target);
            log.debug("Built LLM prompt for target {}: {}", target.getTargetId(), prompt);
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> llmClient.generateText(prompt), llmExecutor);
            future
                    .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .whenComplete((text, throwable) -> {
                        if (throwable == null) {
                            log.debug("LLM response for target {}: {}", target.getTargetId(), text);
                            onSuccess.accept(LlmExplanation.builder()
                                    .source(RiskConstants.EXPLANATION_SOURCE_LLM)
                                    .provider(resolveProviderName())
                                    .targetId(target.getTargetId())
                                    .riskLevel(target.getRiskLevel())
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
                                    ChatErrorCode.LLM_TIMEOUT,
                                    "LLM explanation request timed out."
                            ));
                            return;
                        }

                        log.warn("LLM client failed for target {}, type={}, error={}",
                                target.getTargetId(), cause.getClass().getSimpleName(), cause.getMessage());
                        onError.accept(target, new LlmExplanationError(
                                ChatErrorCode.LLM_REQUEST_FAILED,
                                "LLM explanation request failed."
                        ));
                    });
        }
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

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private String resolveProviderName() {
        return StringUtils.hasText(llmProperties.getProvider()) ? llmProperties.getProvider() : "llm";
    }

    @PreDestroy
    public void shutdownExecutor() {
        llmExecutor.shutdownNow();
    }
}
