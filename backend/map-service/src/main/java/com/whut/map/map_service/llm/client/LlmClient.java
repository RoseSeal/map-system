package com.whut.map.map_service.llm.client;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentStepResult;
import com.whut.map.map_service.llm.agent.FinalText;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.agent.ToolCallRequest;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;

import java.util.List;

public interface LlmClient {

    String NON_SYSTEM_MESSAGE_ERROR = "messages must contain at least one non-system message";

    default String generateText(String prompt) {
        return chat(List.of(new LlmChatMessage(ChatRole.USER, prompt)));
    }

    default String chat(List<LlmChatMessage> messages) {
        List<AgentMessage> agentMessages = toTextAgentMessages(messages);
        AgentStepResult result = chatWithTools(agentMessages, List.of());
        return requireFinalText(result);
    }

    AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools);

    private static List<AgentMessage> toTextAgentMessages(List<LlmChatMessage> messages) {
        if (messages == null) {
            return List.of();
        }
        return messages.stream()
                .filter(m -> m != null)
                .map(m -> (AgentMessage) new TextAgentMessage(m.role(), m.content()))
                .toList();
    }

    private static String requireFinalText(AgentStepResult result) {
        return switch (result) {
            case FinalText ft -> ft.text();
            case ToolCallRequest tcr -> throw new IllegalStateException(
                    "provider returned a tool call when no tools were registered: " + tcr.toolName()
            );
        };
    }
}
