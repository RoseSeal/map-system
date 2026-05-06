package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentLoopOrchestrator;
import com.whut.map.map_service.llm.agent.AgentLoopResult;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.client.LlmTaskType;
import com.whut.map.map_service.llm.agent.trigger.AdvisoryTriggerPort;
import com.whut.map.map_service.llm.agent.tool.AgentToolNames;
import com.whut.map.map_service.llm.config.LlmExecutorConfig;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import com.whut.map.map_service.risk.transport.SseEventFactory;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.AdvisoryPayload;
import com.whut.map.map_service.shared.dto.sse.AdvisoryScope;
import com.whut.map.map_service.shared.dto.sse.AdvisoryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class AdvisoryService implements AdvisoryTriggerPort {

    private static final String ADVISORY_SCHEMA_FAILED = "ADVISORY_SCHEMA_FAILED";

    private final AgentLoopOrchestrator orchestrator;
    private final AdvisoryPromptBuilder promptBuilder;
    private final AdvisoryOutputParser outputParser;
    private final RiskStreamPublisher riskStreamPublisher;
    private final SseEventFactory sseEventFactory;
    private final RiskContextHolder riskContextHolder;
    private final LlmProperties llmProperties;
    private final ExecutorService llmExecutor;

    private final AtomicReference<String> lastAdvisoryId = new AtomicReference<>(null);

    public AdvisoryService(
            AgentLoopOrchestrator orchestrator,
            AdvisoryPromptBuilder promptBuilder,
            AdvisoryOutputParser outputParser,
            RiskStreamPublisher riskStreamPublisher,
            SseEventFactory sseEventFactory,
            RiskContextHolder riskContextHolder,
            LlmProperties llmProperties,
            @Qualifier(LlmExecutorConfig.LLM_EXECUTOR) ExecutorService llmExecutor
    ) {
        this.orchestrator = orchestrator;
        this.promptBuilder = promptBuilder;
        this.outputParser = outputParser;
        this.riskStreamPublisher = riskStreamPublisher;
        this.sseEventFactory = sseEventFactory;
        this.riskContextHolder = riskContextHolder;
        this.llmProperties = llmProperties;
        this.llmExecutor = llmExecutor;
    }

    @Override
    public void onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete) {
        try {
            llmExecutor.execute(() -> generate(snapshot, onComplete));
        } catch (RejectedExecutionException e) {
            log.warn("Advisory executor rejected task; releasing generating flag");
            onComplete.run();
        }
    }

    private void generate(AgentSnapshot snapshot, Runnable onComplete) {
        try {
            LlmProperties.Advisory config = llmProperties.getAdvisory();

            Long currentVersion = riskContextHolder.getVersion();
            if (currentVersion != null) {
                long lag = currentVersion - snapshot.snapshotVersion();
                if (lag > config.getMaxSnapshotVersionLag()) {
                    log.info("Advisory discarded: snapshot lag {} > threshold {}", lag, config.getMaxSnapshotVersionLag());
                    return;
                }
            }

            var initialMessages = promptBuilder.build(snapshot);
            AgentLoopResult loopResult = orchestrator.run(
                    LlmTaskType.AGENT,
                    snapshot,
                    initialMessages,
                    config.getMaxIterations(),
                    com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

            switch (loopResult) {
                case AgentLoopResult.Completed completed -> {
                    if (completed.toolCallCount() == 0) {
                        log.warn("Advisory schema failure: LLM returned final text without calling any tools");
                        publishSchemaFailed();
                        return;
                    }
                    AdvisoryOutputParser.ParsedAdvisory parsed = outputParser.parse(completed.finalText(), snapshot);
                    if (parsed == null) {
                        log.warn("Advisory schema failure: output parser returned null");
                        publishSchemaFailed();
                        return;
                    }
                    if (!hydrologyEvidenceHasToolSource(parsed, completed)) {
                        log.warn("Advisory schema failure: hydrology evidence appeared without hydrology tool call");
                        publishSchemaFailed();
                        return;
                    }
                    publishAdvisory(snapshot, parsed, config, completed.provider());
                }
                case AgentLoopResult.MaxIterationsExceeded exceeded -> {
                    log.warn("Advisory loop exceeded max iterations ({}), discarding", exceeded.iterations());
                    publishSchemaFailed();
                }
                case AgentLoopResult.ProviderFailed failed -> {
                    log.warn("Advisory provider failed: {} - {}", failed.errorCode(), failed.message());
                    riskStreamPublisher.publishError(failed.errorCode(), failed.message(), null);
                }
                case AgentLoopResult.ToolFailed failed -> {
                    log.warn("Advisory tool {} failed: {}", failed.toolName(), failed.message());
                    riskStreamPublisher.publishError("LLM_REQUEST_FAILED", "Tool execution failed: " + failed.toolName(), null);
                }
            }
        } catch (Exception e) {
            log.error("Unexpected error during advisory generation", e);
        } finally {
            onComplete.run();
        }
    }

    private void publishAdvisory(
            AgentSnapshot snapshot,
            AdvisoryOutputParser.ParsedAdvisory parsed,
            LlmProperties.Advisory config,
            String provider
    ) {
        String advisoryId = UUID.randomUUID().toString();
        String prevId = lastAdvisoryId.getAndSet(advisoryId);

        Instant now = Instant.now();
        String validUntil = now.plus(config.getValidSeconds(), ChronoUnit.SECONDS).toString();

        AdvisoryPayload payload = AdvisoryPayload.builder()
                .eventId(sseEventFactory.generateEventId())
                .advisoryId(advisoryId)
                .riskObjectId(snapshot.riskContext() != null ? snapshotRiskObjectId(snapshot) : null)
                .snapshotVersion(snapshot.snapshotVersion())
                .scope(AdvisoryScope.SCENE)
                .status(AdvisoryStatus.ACTIVE)
                .supersedesId(prevId)
                .validUntil(validUntil)
                .riskLevel(highestRiskLevel(snapshot))
                .provider(StringUtils.hasText(provider) ? provider : "unknown")
                .timestamp(now.toString())
                .summary(parsed.summary())
                .affectedTargets(parsed.affectedTargets())
                .recommendedAction(parsed.recommendedAction())
                .evidenceItems(parsed.evidenceItems())
                .build();

        riskStreamPublisher.publishAdvisory(payload);
        log.info("Advisory published: id={} supersedes={} riskLevel={}", advisoryId, prevId, payload.getRiskLevel());
    }

    private void publishSchemaFailed() {
        riskStreamPublisher.publishError(ADVISORY_SCHEMA_FAILED, "Advisory generation failed: schema validation error", null);
    }

    private boolean hydrologyEvidenceHasToolSource(
            AdvisoryOutputParser.ParsedAdvisory parsed,
            AgentLoopResult.Completed completed
    ) {
        boolean hasHydrologyEvidence = parsed.evidenceItems() != null
                && parsed.evidenceItems().stream()
                .filter(Objects::nonNull)
                .map(String::toLowerCase)
                .anyMatch(item -> item.contains("[source: hydrology]"));
        if (!hasHydrologyEvidence) {
            return true;
        }
        return completed.calledToolNames().contains(AgentToolNames.QUERY_BATHYMETRY)
                || completed.calledToolNames().contains(AgentToolNames.EVALUATE_MANEUVER_HYDROLOGY);
    }

    private RiskLevel highestRiskLevel(AgentSnapshot snapshot) {
        if (snapshot.riskContext() == null || snapshot.riskContext().getTargets() == null) {
            return RiskLevel.SAFE;
        }
        return snapshot.riskContext().getTargets().stream()
                .map(LlmRiskTargetContext::getRiskLevel)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(RiskLevel.SAFE);
    }

    private String snapshotRiskObjectId(AgentSnapshot snapshot) {
        return "snapshot-" + snapshot.snapshotVersion();
    }
}
