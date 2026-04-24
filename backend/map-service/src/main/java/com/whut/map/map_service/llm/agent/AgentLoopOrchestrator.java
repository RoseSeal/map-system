package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.agent.tool.AgentToolRegistry;
import com.whut.map.map_service.llm.client.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopOrchestrator {

    private final LlmClient llmClient;
    private final AgentToolRegistry toolRegistry;

    public AgentLoopResult run(
            AgentSnapshot snapshot,
            List<AgentMessage> initialMessages,
            int maxIterations
    ) {
        List<AgentMessage> messages = new ArrayList<>(initialMessages);
        List<ToolDefinition> toolDefs = toolRegistry.getToolDefinitions();
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
                    messages.add(new ToolCallAgentMessage(tcr.callId(), tcr.toolName(), tcr.arguments()));
                    ToolResult toolResult;
                    try {
                        toolResult = toolRegistry.execute(
                                new ToolCall(tcr.callId(), tcr.toolName(), tcr.arguments()),
                                snapshot
                        );
                    } catch (Exception e) {
                        log.warn("Tool {} (callId={}) threw unexpected exception: {}", tcr.toolName(), tcr.callId(), e.getMessage());
                        return AgentLoopResult.toolFailed(tcr.callId(), tcr.toolName(), e.getMessage(), e);
                    }
                    messages.add(new ToolResultAgentMessage(toolResult.callId(), toolResult.toolName(), toolResult.payload()));
                    toolCallCount++;
                }
                case FinalText ft -> {
                    return AgentLoopResult.completed(ft.text(), iteration, toolCallCount);
                }
            }
        }

        return AgentLoopResult.maxIterationsExceeded(iteration, toolCallCount);
    }
}
