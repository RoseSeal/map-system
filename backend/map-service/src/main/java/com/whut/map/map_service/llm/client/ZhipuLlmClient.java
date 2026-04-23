package com.whut.map.map_service.llm.client;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatFunction;
import ai.z.openapi.service.model.ChatFunctionCall;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.ChatTool;
import ai.z.openapi.service.model.ChatToolType;
import ai.z.openapi.service.model.ToolCalls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentStepResult;
import com.whut.map.map_service.llm.agent.FinalText;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.agent.ToolCallAgentMessage;
import com.whut.map.map_service.llm.agent.ToolCallRequest;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.ToolResultAgentMessage;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "zhipu")
public class ZhipuLlmClient implements LlmClient {

    private final ZhipuAiClient zhipuAiClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Override
    public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
        ChatCompletionCreateParams request = buildRequest(messages, tools);
        ChatCompletionResponse response = zhipuAiClient.chat().createChatCompletion(request);

        if (response == null) {
            throw new IllegalStateException("Zhipu response is null");
        }

        if (!response.isSuccess()) {
            throw new IllegalStateException("Zhipu request failed: " + response.getMsg());
        }

        if (response.getData() == null
                || response.getData().getChoices() == null
                || response.getData().getChoices().isEmpty()
                || response.getData().getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("Zhipu response contains no message");
        }

        return toAgentStepResult(response);
    }

    ChatCompletionCreateParams buildRequest(List<AgentMessage> messages, List<ToolDefinition> tools) {
        var builder = ChatCompletionCreateParams.builder()
                .model(llmProperties.getZhipu().getModel())
                .messages(toZhipuMessages(messages));

        if (tools != null && !tools.isEmpty()) {
            builder.tools(tools.stream().map(this::toZhipuTool).toList());
        }

        return builder.build();
    }

    List<ChatMessage> toZhipuMessages(List<AgentMessage> messages) {
        List<ChatMessage> zhipuMessages = messages == null ? List.of() : messages.stream()
                .filter(m -> m != null)
                .map(this::toZhipuChatMessage)
                .toList();

        if (zhipuMessages.isEmpty()) {
            throw new IllegalArgumentException(LlmClient.NON_SYSTEM_MESSAGE_ERROR);
        }

        return zhipuMessages;
    }

    AgentStepResult toAgentStepResult(ChatCompletionResponse response) {
        ChatMessage message = response.getData().getChoices().get(0).getMessage();
        List<ToolCalls> toolCalls = message.getToolCalls();

        if (toolCalls != null && !toolCalls.isEmpty()) {
            ToolCalls first = toolCalls.get(0);
            String callId = (first.getId() != null && !first.getId().isBlank())
                    ? first.getId()
                    : UUID.randomUUID().toString();
            ChatFunctionCall functionCall = first.getFunction();
            String toolName = functionCall.getName();
            JsonNode argsNode = functionCall.getArguments();
            ObjectNode arguments = (argsNode instanceof ObjectNode on)
                    ? on
                    : objectMapper.createObjectNode();
            return new ToolCallRequest(callId, toolName, arguments);
        }

        Object content = message.getContent();
        if (content == null) {
            throw new IllegalStateException("Zhipu response contains no text");
        }

        String text = String.valueOf(content);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            throw new IllegalStateException("Zhipu response contains no text");
        }

        return new FinalText(text);
    }

    private ChatMessage toZhipuChatMessage(AgentMessage message) {
        return switch (message) {
            case TextAgentMessage text -> ChatMessage.builder()
                    .role(toZhipuRole(text.role()).value())
                    .content(text.content())
                    .build();
            case ToolCallAgentMessage toolCall -> {
                ChatFunctionCall functionCall = ChatFunctionCall.builder()
                        .name(toolCall.toolName())
                        .arguments(toolCall.arguments())
                        .build();
                ToolCalls tc = ToolCalls.builder()
                        .id(toolCall.callId())
                        .type(ChatToolType.FUNCTION.value())
                        .function(functionCall)
                        .build();
                yield ChatMessage.builder()
                        .role(ChatMessageRole.ASSISTANT.value())
                        .toolCalls(List.of(tc))
                        .build();
            }
            case ToolResultAgentMessage toolResult -> ChatMessage.builder()
                    .role("tool")
                    .toolCallId(toolResult.callId())
                    .content(toolResult.result().toString())
                    .build();
        };
    }

    private ChatTool toZhipuTool(ToolDefinition toolDef) {
        ChatFunction function = ChatFunction.builder()
                .name(toolDef.name())
                .description(toolDef.description())
                .parameters(toolDef.parametersJsonSchema())
                .build();
        return ChatTool.builder()
                .type(ChatToolType.FUNCTION.value())
                .function(function)
                .build();
    }

    private ChatMessageRole toZhipuRole(ChatRole role) {
        return switch (role) {
            case SYSTEM -> ChatMessageRole.SYSTEM;
            case USER -> ChatMessageRole.USER;
            case ASSISTANT -> ChatMessageRole.ASSISTANT;
        };
    }
}
