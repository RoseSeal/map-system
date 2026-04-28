package com.whut.map.map_service.llm.agent.advisory;

import com.whut.map.map_service.llm.agent.AgentLoopOrchestrator;
import com.whut.map.map_service.llm.agent.AgentLoopResult;
import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.client.LlmTaskType;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.llm.dto.LlmRiskTargetContext;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import com.whut.map.map_service.risk.transport.SseEventFactory;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.shared.dto.sse.AdvisoryActionType;
import com.whut.map.map_service.shared.dto.sse.AdvisoryPayload;
import com.whut.map.map_service.shared.dto.sse.AdvisoryUrgency;
import com.whut.map.map_service.shared.dto.sse.RecommendedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvisoryServiceTest {

    @Mock
    private AgentLoopOrchestrator orchestrator;
    @Mock
    private AdvisoryPromptBuilder promptBuilder;
    @Mock
    private AdvisoryOutputParser outputParser;
    @Mock
    private RiskStreamPublisher riskStreamPublisher;
    @Mock
    private SseEventFactory sseEventFactory;
    @Mock
    private RiskContextHolder riskContextHolder;

    private LlmProperties llmProperties;
    private AdvisoryService service;

    private AgentSnapshot snapshotV(long version) {
        List<LlmRiskTargetContext> targets = List.of(
                LlmRiskTargetContext.builder().targetId("t1").riskLevel(RiskLevel.ALARM)
                        .dcpaNm(0.1).tcpaSec(100).longitude(114.0).latitude(30.0)
                        .speedKn(8.0).courseDeg(90.0).build()
        );
        return new AgentSnapshot(version, LlmRiskContext.builder().targets(targets).build(), Map.of());
    }

    private AdvisoryOutputParser.ParsedAdvisory validParsed() {
        RecommendedAction action = RecommendedAction.builder()
                .type(AdvisoryActionType.COURSE_CHANGE)
                .description("右转")
                .urgency(AdvisoryUrgency.IMMEDIATE)
                .build();
        return new AdvisoryOutputParser.ParsedAdvisory("摘要", List.of("t1"), action, List.of("DCPA 0.1 nm"));
    }

    // Inline executor runs tasks synchronously for deterministic tests
    private static final ExecutorService SYNC_EXECUTOR = new AbstractExecutorService() {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public java.util.List<Runnable> shutdownNow() { return java.util.List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };

    @BeforeEach
    void setUp() {
        llmProperties = new LlmProperties();
        llmProperties.getAdvisory().setMaxIterations(5);
        llmProperties.getAdvisory().setMaxSnapshotVersionLag(5);
        llmProperties.getAdvisory().setValidSeconds(120);

        service = new AdvisoryService(
                orchestrator, promptBuilder, outputParser, riskStreamPublisher,
                sseEventFactory, riskContextHolder, llmProperties,
                SYNC_EXECUTOR
        );

        org.mockito.Mockito.lenient().when(promptBuilder.build(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(sseEventFactory.generateEventId()).thenReturn("evt-1");
    }

    @Test
    void successfulGenerationPublishesAdvisoryAndCallsOnComplete() {
        when(riskContextHolder.getVersion()).thenReturn(10L);
        when(orchestrator.run(any(LlmTaskType.class), any(), anyList(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("text", 3, 2, null, "zhipu"));
        when(outputParser.parse(eq("text"), any())).thenReturn(validParsed());

        AtomicBoolean completed = new AtomicBoolean(false);
        service.onAdvisoryTrigger(snapshotV(10L), () -> completed.set(true));

        ArgumentCaptor<AdvisoryPayload> captor = ArgumentCaptor.forClass(AdvisoryPayload.class);
        verify(riskStreamPublisher).publishAdvisory(captor.capture());
        assertThat(captor.getValue().getSummary()).isEqualTo("摘要");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo(RiskLevel.ALARM);
        assertThat(captor.getValue().getProvider()).isEqualTo("zhipu");
        assertThat(completed.get()).isTrue();
    }

    @Test
    void schemaFailurePublishesErrorAndCallsOnComplete() {
        when(riskContextHolder.getVersion()).thenReturn(10L);
        when(orchestrator.run(any(LlmTaskType.class), any(), anyList(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("bad", 3, 2));
        when(outputParser.parse(any(), any())).thenReturn(null);

        AtomicBoolean completed = new AtomicBoolean(false);
        service.onAdvisoryTrigger(snapshotV(10L), () -> completed.set(true));

        verify(riskStreamPublisher).publishError(eq("ADVISORY_SCHEMA_FAILED"), any(), any());
        verify(riskStreamPublisher, never()).publishAdvisory(any());
        assertThat(completed.get()).isTrue();
    }

    @Test
    void zeroToolCallCountTreatedAsSchemaFailure() {
        when(riskContextHolder.getVersion()).thenReturn(10L);
        when(orchestrator.run(any(LlmTaskType.class), any(), anyList(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("guessed", 1, 0));

        AtomicBoolean completed = new AtomicBoolean(false);
        service.onAdvisoryTrigger(snapshotV(10L), () -> completed.set(true));

        verify(riskStreamPublisher).publishError(eq("ADVISORY_SCHEMA_FAILED"), any(), any());
        verify(riskStreamPublisher, never()).publishAdvisory(any());
        assertThat(completed.get()).isTrue();
    }

    @Test
    void snapshotVersionLagExceedsThresholdDiscardsSilently() {
        when(riskContextHolder.getVersion()).thenReturn(20L); // lag = 20 - 10 = 10 > 5

        AtomicBoolean completed = new AtomicBoolean(false);
        service.onAdvisoryTrigger(snapshotV(10L), () -> completed.set(true));

        verify(riskStreamPublisher, never()).publishAdvisory(any());
        verify(riskStreamPublisher, never()).publishError(any(), any(), any());
        assertThat(completed.get()).isTrue();
    }

    @Test
    void supersedesIdFilledFromPreviousAdvisory() {
        when(riskContextHolder.getVersion()).thenReturn(10L);
        when(orchestrator.run(any(LlmTaskType.class), any(), anyList(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("text", 3, 2));
        when(outputParser.parse(any(), any())).thenReturn(validParsed());

        ArgumentCaptor<AdvisoryPayload> captor = ArgumentCaptor.forClass(AdvisoryPayload.class);

        service.onAdvisoryTrigger(snapshotV(10L), () -> {});
        service.onAdvisoryTrigger(snapshotV(10L), () -> {});

        verify(riskStreamPublisher, org.mockito.Mockito.times(2)).publishAdvisory(captor.capture());
        List<AdvisoryPayload> payloads = captor.getAllValues();
        assertThat(payloads.get(0).getSupersedesId()).isNull();
        assertThat(payloads.get(1).getSupersedesId()).isEqualTo(payloads.get(0).getAdvisoryId());
    }

    @Test
    void providerFailurePublishesErrorAndCallsOnComplete() {
        when(riskContextHolder.getVersion()).thenReturn(10L);
        when(orchestrator.run(any(LlmTaskType.class), any(), anyList(), anyInt(), any()))
                .thenReturn(AgentLoopResult.providerFailed("LLM_TIMEOUT", "timeout", null));

        AtomicBoolean completed = new AtomicBoolean(false);
        service.onAdvisoryTrigger(snapshotV(10L), () -> completed.set(true));

        verify(riskStreamPublisher).publishError(contains("LLM_TIMEOUT"), any(), any());
        assertThat(completed.get()).isTrue();
    }
}
