package com.whut.map.map_service.llm.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.tool.AgentToolRegistry;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.LlmRiskContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentLoopOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmClient llmClient;

    @Mock
    private AgentToolRegistry toolRegistry;

    private AgentLoopOrchestrator orchestrator;

    private AgentSnapshot emptySnapshot() {
        return new AgentSnapshot(1L, LlmRiskContext.builder().targets(List.of()).build(), Map.of());
    }

    @BeforeEach
    void setUp() {
        when(toolRegistry.getToolDefinitions()).thenReturn(List.of());
        orchestrator = new AgentLoopOrchestrator(llmClient, toolRegistry);
    }

    @Test
    void firstRoundFinalTextReturnsCompleted() {
        when(llmClient.chatWithTools(anyList(), anyList())).thenReturn(new FinalText("done"));

        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 5, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        assertThat(result).isInstanceOf(AgentLoopResult.Completed.class);
        AgentLoopResult.Completed completed = (AgentLoopResult.Completed) result;
        assertThat(completed.finalText()).isEqualTo("done");
        assertThat(completed.iterations()).isEqualTo(1);
        assertThat(completed.toolCallCount()).isEqualTo(0);
    }

    @Test
    void toolCallThenFinalTextCompletesWithOneToolCall() {
        var tcr = new ToolCallRequest("c1", "get_risk_snapshot", MAPPER.createObjectNode());
        var toolResult = new ToolResult("c1", "get_risk_snapshot", MAPPER.createObjectNode().put("status", "OK"));

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(tcr)
                .thenReturn(new FinalText("advisory text"));
        when(toolRegistry.execute(any(ToolCall.class), any(AgentSnapshot.class))).thenReturn(toolResult);

        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 5, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        assertThat(result).isInstanceOf(AgentLoopResult.Completed.class);
        AgentLoopResult.Completed completed = (AgentLoopResult.Completed) result;
        assertThat(completed.toolCallCount()).isEqualTo(1);
        assertThat(completed.iterations()).isEqualTo(2);
    }

    @Test
    void emitsStableStepIdsForToolLifecycleAndCarriesFinalizingStepId() {
        var tcr = new ToolCallRequest("call-123", "get_risk_snapshot", MAPPER.createObjectNode());
        var toolResult = new ToolResult("call-123", "get_risk_snapshot", MAPPER.createObjectNode().put("status", "OK"));

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(tcr)
                .thenReturn(new FinalText("advisory text"));
        when(toolRegistry.execute(any(ToolCall.class), any(AgentSnapshot.class))).thenReturn(toolResult);

        List<AgentStepEvent> events = new ArrayList<>();
        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 5, events::add);

        assertThat(result).isInstanceOf(AgentLoopResult.Completed.class);
        AgentLoopResult.Completed completed = (AgentLoopResult.Completed) result;
        assertThat(events).hasSize(3);

        assertThat(events.get(0).status()).isEqualTo(AgentStepStatus.RUNNING);
        assertThat(events.get(0).stepId()).isEqualTo("call-123");

        assertThat(events.get(1).status()).isEqualTo(AgentStepStatus.SUCCEEDED);
        assertThat(events.get(1).stepId()).isEqualTo("call-123");

        assertThat(events.get(2).status()).isEqualTo(AgentStepStatus.FINALIZING);
        assertThat(completed.finalizingStepId()).isEqualTo(events.get(2).stepId());
    }

    @Test
    void toolCallMessagesAreAppendedInOrder() {
        var tcr = new ToolCallRequest("c1", "get_risk_snapshot", MAPPER.createObjectNode());
        var toolResult = new ToolResult("c1", "get_risk_snapshot", MAPPER.createObjectNode().put("status", "OK"));

        when(llmClient.chatWithTools(anyList(), anyList()))
                .thenReturn(tcr)
                .thenReturn(new FinalText("text"));
        when(toolRegistry.execute(any(), any())).thenReturn(toolResult);

        orchestrator.run(emptySnapshot(), List.of(), 5, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        // First call: empty messages; second call: 2 messages (tool call + tool result)
        verify(llmClient, times(2)).chatWithTools(anyList(), anyList());
    }

    @Test
    void maxIterationsExceededWhenProviderKeepsCallingTools() {
        var tcr = new ToolCallRequest("c1", "get_risk_snapshot", MAPPER.createObjectNode());
        var toolResult = new ToolResult("c1", "get_risk_snapshot", MAPPER.createObjectNode());
        when(llmClient.chatWithTools(anyList(), anyList())).thenReturn(tcr);
        when(toolRegistry.execute(any(), any())).thenReturn(toolResult);

        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 3, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        assertThat(result).isInstanceOf(AgentLoopResult.MaxIterationsExceeded.class);
        AgentLoopResult.MaxIterationsExceeded exceeded = (AgentLoopResult.MaxIterationsExceeded) result;
        assertThat(exceeded.iterations()).isEqualTo(3);
        assertThat(exceeded.toolCallCount()).isEqualTo(3);
    }

    @Test
    void registryUnexpectedExceptionReturnsToolFailed() {
        var tcr = new ToolCallRequest("c1", "boom_tool", MAPPER.createObjectNode());
        when(llmClient.chatWithTools(anyList(), anyList())).thenReturn(tcr);
        when(toolRegistry.execute(any(), any())).thenThrow(new RuntimeException("unexpected"));

        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 5, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        assertThat(result).isInstanceOf(AgentLoopResult.ToolFailed.class);
        AgentLoopResult.ToolFailed failed = (AgentLoopResult.ToolFailed) result;
        assertThat(failed.toolName()).isEqualTo("boom_tool");
        assertThat(failed.callId()).isEqualTo("c1");
    }

    @Test
    void providerExceptionReturnsProviderFailed() {
        when(llmClient.chatWithTools(anyList(), anyList())).thenThrow(new RuntimeException("provider down"));

        AgentLoopResult result = orchestrator.run(emptySnapshot(), List.of(), 5, com.whut.map.map_service.llm.agent.AgentStepSink.NOOP);

        assertThat(result).isInstanceOf(AgentLoopResult.ProviderFailed.class);
        AgentLoopResult.ProviderFailed failed = (AgentLoopResult.ProviderFailed) result;
        assertThat(failed.errorCode()).isEqualTo("LLM_REQUEST_FAILED");
    }
}
