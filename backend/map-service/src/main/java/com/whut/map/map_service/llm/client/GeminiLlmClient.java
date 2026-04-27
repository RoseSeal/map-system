package com.whut.map.map_service.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Tool;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@Qualifier("gemini")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.enabled", havingValue = "true")
public class GeminiLlmClient implements LlmClient {

    private final Client geminiClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    @Override
    public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
        List<Content> contents = buildContents(messages);
        GenerateContentConfig config = buildConfig(messages, tools);
        LlmProperties.RetryProperties retryProperties = llmProperties.getGemini().getRetry();
        int maxRetries = retryProperties == null ? 0 : retryProperties.getMaxRetries();
        long backoffMs = retryProperties == null ? 1000L : retryProperties.getInitialBackoffMs();

        for (int retryIndex = 0; ; retryIndex++) {
            try {
                GenerateContentResponse response = callModel(
                        llmProperties.getGemini().getModel(), contents, config);
                return toAgentStepResult(response);
            } catch (ServerException e) {
                if (!shouldRetry503(e, retryIndex, maxRetries)) {
                    throw e;
                }
                long delayMs = Math.max(1000L, backoffMs);
                log.warn("Gemini request got 503 UNAVAILABLE on attempt {} of {}. Retrying after {} ms.",
                        retryIndex + 1, maxRetries + 1, delayMs);
                sleepBackoff(delayMs);
                backoffMs = nextBackoff(delayMs);
            }
        }
    }

    GenerateContentResponse callModel(String model, List<Content> contents, GenerateContentConfig config) {
        return geminiClient.models.generateContent(model, contents, config);
    }

    List<Content> buildContents(List<AgentMessage> messages) {
        List<Content> contents = messages == null ? List.of() : messages.stream()
                .filter(m -> m != null)
                .filter(m -> !(m instanceof TextAgentMessage tam && tam.role() == ChatRole.SYSTEM))
                .map(this::toGeminiContent)
                .toList();

        if (contents.isEmpty()) {
            throw new IllegalArgumentException(LlmClient.NON_SYSTEM_MESSAGE_ERROR);
        }

        return contents;
    }

    GenerateContentConfig buildConfig(List<AgentMessage> messages, List<ToolDefinition> tools) {
        String systemInstruction = messages == null ? null : messages.stream()
                .filter(m -> m instanceof TextAgentMessage tam && tam.role() == ChatRole.SYSTEM)
                .map(m -> ((TextAgentMessage) m).content())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);

        GenerateContentConfig.Builder builder = GenerateContentConfig.builder();
        boolean hasConfig = false;

        if (systemInstruction != null && !systemInstruction.isBlank()) {
            builder.systemInstruction(Content.fromParts(Part.fromText(systemInstruction)));
            hasConfig = true;
        }

        if (tools != null && !tools.isEmpty()) {
            List<FunctionDeclaration> declarations = tools.stream()
                    .map(this::toFunctionDeclaration)
                    .toList();
            builder.tools(List.of(Tool.builder().functionDeclarations(declarations).build()));
            hasConfig = true;
        }

        return hasConfig ? builder.build() : null;
    }

    AgentStepResult toAgentStepResult(GenerateContentResponse response) {
        if (response == null) {
            throw new IllegalStateException("Gemini response is null");
        }

        var functionCalls = response.functionCalls();
        if (!functionCalls.isEmpty()) {
            FunctionCall fc = functionCalls.get(0);
            String toolName = fc.name().orElseThrow(
                    () -> new IllegalStateException("Gemini function call has no name"));
            Map<String, Object> argsMap = fc.args().orElse(Map.of());
            return new ToolCallRequest(UUID.randomUUID().toString(), toolName,
                    objectMapper.valueToTree(argsMap));
        }

        String text = response.text();
        if (text != null && !text.isBlank()) {
            return new FinalText(text);
        }

        throw new IllegalStateException("Gemini response contains neither function call nor text");
    }

    private Content toGeminiContent(AgentMessage message) {
        return switch (message) {
            case TextAgentMessage text -> Content.builder()
                    .role(toGeminiRole(text.role()))
                    .parts(List.of(Part.fromText(text.content())))
                    .build();
            case ToolCallAgentMessage toolCall -> Content.builder()
                    .role("model")
                    .parts(List.of(Part.fromFunctionCall(
                            toolCall.toolName(),
                            objectMapper.convertValue(toolCall.arguments(), Map.class))))
                    .build();
            case ToolResultAgentMessage toolResult -> Content.builder()
                    .role("user")
                    .parts(List.of(Part.fromFunctionResponse(
                            toolResult.toolName(),
                            objectMapper.convertValue(toolResult.result(), Map.class))))
                    .build();
        };
    }

    private FunctionDeclaration toFunctionDeclaration(ToolDefinition toolDef) {
        try {
            Schema schema = Schema.fromJson(
                    objectMapper.writeValueAsString(toolDef.parametersJsonSchema()));
            return FunctionDeclaration.builder()
                    .name(toolDef.name())
                    .description(toolDef.description())
                    .parameters(schema)
                    .build();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize tool schema for: " + toolDef.name(), e);
        }
    }

    private String toGeminiRole(ChatRole role) {
        return role == ChatRole.ASSISTANT ? "model" : "user";
    }

    private boolean shouldRetry503(ServerException exception, int retryIndex, int maxRetries) {
        return exception.code() == 503 && retryIndex < maxRetries;
    }

    private long nextBackoff(long currentBackoffMs) {
        return Math.min(currentBackoffMs * 2L, 4000L);
    }

    private void sleepBackoff(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini retry backoff was interrupted", e);
        }
    }
}
