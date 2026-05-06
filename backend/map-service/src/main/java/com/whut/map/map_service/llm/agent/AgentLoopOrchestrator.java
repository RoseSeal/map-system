package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.agent.tool.AgentToolRegistry;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.client.LlmClientRegistry;
import com.whut.map.map_service.llm.client.LlmProvider;
import com.whut.map.map_service.llm.client.LlmTaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopOrchestrator {

    private final LlmClientRegistry llmClientRegistry;
    private final AgentToolRegistry toolRegistry;

    public AgentLoopResult run(
            LlmTaskType taskType,
            AgentSnapshot snapshot,
            List<AgentMessage> initialMessages,
            int maxIterations
    ) {
        return run(taskType, snapshot, initialMessages, maxIterations, AgentStepSink.NOOP);
    }

    public AgentLoopResult run(
            AgentSnapshot snapshot,
            List<AgentMessage> initialMessages,
            int maxIterations
    ) {
        return run(LlmTaskType.AGENT, snapshot, initialMessages, maxIterations, AgentStepSink.NOOP);
    }

    public AgentLoopResult run(
            AgentSnapshot snapshot,
            List<AgentMessage> initialMessages,
            int maxIterations,
            AgentStepSink stepSink
    ) {
        return run(LlmTaskType.AGENT, snapshot, initialMessages, maxIterations, stepSink);
    }

    public AgentLoopResult run(
            LlmTaskType taskType,
            AgentSnapshot snapshot,
            List<AgentMessage> initialMessages,
            int maxIterations,
            AgentStepSink stepSink
    ) {
        LlmProvider provider;
        LlmClient llmClient;
        try {
            provider = llmClientRegistry.resolveProviderForTask(taskType);
            llmClient = llmClientRegistry.find(provider)
                    .orElseThrow(() -> new IllegalStateException("provider is unavailable for task " + taskType + ": " + provider));
        } catch (Exception e) {
            log.warn("Provider resolution failed for task {}: {}", taskType, e.getMessage());
            return AgentLoopResult.providerFailed("LLM_REQUEST_FAILED", e.getMessage(), e);
        }

        List<AgentMessage> messages = new ArrayList<>(initialMessages);
        List<ToolDefinition> toolDefs = toolRegistry.getToolDefinitions();
        List<String> calledToolNames = new ArrayList<>();
        int iteration = 0;
        int toolCallCount = 0;

        while (iteration < maxIterations) {
            AgentStepResult stepResult;
            try {
                stepResult = llmClient.chatWithTools(messages, toolDefs);
            } catch (Exception e) {
                log.warn("LLM provider failed at iteration {}: {}", iteration, e.getMessage());
                return AgentLoopResult.providerFailed("LLM_REQUEST_FAILED", e.getMessage(), e);
            }
            iteration++;

            switch (stepResult) {
                case ToolCallRequest tcr -> {
                    String stepId = resolveStepId(tcr.callId());
                    stepSink.accept(new AgentStepEvent(
                            stepId, tcr.toolName(), AgentStepStatus.RUNNING, "正在调用工具"));
                    messages.add(new ToolCallAgentMessage(tcr.callId(), tcr.toolName(), tcr.arguments()));
                    ToolResult toolResult;
                    try {
                        toolResult = toolRegistry.execute(
                                new ToolCall(tcr.callId(), tcr.toolName(), tcr.arguments()),
                                snapshot
                        );
                        stepSink.accept(new AgentStepEvent(
                                stepId, tcr.toolName(), AgentStepStatus.SUCCEEDED, "工具调用完成"));
                    } catch (Exception e) {
                        log.warn("Tool {} (callId={}) threw unexpected exception: {}", tcr.toolName(), tcr.callId(), e.getMessage());
                        stepSink.accept(new AgentStepEvent(
                                stepId, tcr.toolName(), AgentStepStatus.FAILED, "工具调用失败"));
                        return AgentLoopResult.toolFailed(tcr.callId(), tcr.toolName(), e.getMessage(), e);
                    }
                    messages.add(new ToolResultAgentMessage(toolResult.callId(), toolResult.toolName(), toolResult.payload()));
                    toolCallCount++;
                    calledToolNames.add(tcr.toolName());
                }
                case FinalText ft -> {
                    String finalizingStepId = UUID.randomUUID().toString();
                    stepSink.accept(new AgentStepEvent(
                            finalizingStepId, null, AgentStepStatus.FINALIZING, "正在整理态势"));
                    return AgentLoopResult.completed(
                            ft.text(),
                            iteration,
                            toolCallCount,
                            finalizingStepId,
                            provider.getValue(),
                            calledToolNames
                    );
                }
            }
        }

        return AgentLoopResult.maxIterationsExceeded(iteration, toolCallCount);
    }

    private String resolveStepId(String callId) {
        if (callId == null || callId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return callId;
    }
}
