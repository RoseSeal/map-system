package com.whut.map.map_service.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode emptyObject() {
        return MAPPER.createObjectNode();
    }

    private GeminiLlmClient client() {
        return new GeminiLlmClient(null, new LlmProperties(), MAPPER);
    }

    // --- buildConfig ---

    @Test
    void buildConfigMapsSystemInstruction() {
        GenerateContentConfig config = client().buildConfig(List.of(
                new TextAgentMessage(ChatRole.SYSTEM, "system prompt"),
                new TextAgentMessage(ChatRole.USER, "hello")
        ), List.of());

        assertThat(config).isNotNull();
        assertThat(config.systemInstruction()).isPresent();
        assertThat(config.systemInstruction().orElseThrow().parts()).isPresent();
        assertThat(config.systemInstruction().orElseThrow().parts().orElseThrow()).hasSize(1);
        assertThat(config.systemInstruction().orElseThrow().parts().orElseThrow().get(0).text())
                .contains("system prompt");
    }

    @Test
    void buildConfigReturnsNullWhenNoSystemAndNoTools() {
        GenerateContentConfig config = client().buildConfig(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")),
                List.of()
        );

        assertThat(config).isNull();
    }

    @Test
    void buildConfigIncludesToolDeclarations() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        List<ToolDefinition> tools = List.of(new ToolDefinition("myTool", "does something", schema));

        GenerateContentConfig config = client().buildConfig(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")),
                tools
        );

        assertThat(config).isNotNull();
        assertThat(config.tools()).isPresent();
        assertThat(config.tools().orElseThrow()).hasSize(1);
        assertThat(config.tools().orElseThrow().get(0).functionDeclarations()).isPresent();
        assertThat(config.tools().orElseThrow().get(0).functionDeclarations().orElseThrow())
                .hasSize(1);
        assertThat(config.tools().orElseThrow().get(0).functionDeclarations().orElseThrow()
                .get(0).name()).contains("myTool");
    }

    // --- buildContents ---

    @Test
    void buildContentsKeepsConversationRoles() {
        List<Content> contents = client().buildContents(List.of(
                new TextAgentMessage(ChatRole.SYSTEM, "system prompt"),
                new TextAgentMessage(ChatRole.USER, "hello"),
                new TextAgentMessage(ChatRole.ASSISTANT, "previous reply"),
                new TextAgentMessage(ChatRole.USER, "follow up")
        ));

        assertThat(contents).hasSize(3);
        assertThat(contents.get(0).role()).contains("user");
        assertThat(contents.get(0).text()).isEqualTo("hello");
        assertThat(contents.get(1).role()).contains("model");
        assertThat(contents.get(1).text()).isEqualTo("previous reply");
        assertThat(contents.get(2).role()).contains("user");
        assertThat(contents.get(2).text()).isEqualTo("follow up");
    }

    @Test
    void buildContentsRejectsSystemOnlyMessages() {
        assertThatThrownBy(() -> client().buildContents(List.of(
                new TextAgentMessage(ChatRole.SYSTEM, "system prompt")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LlmClient.NON_SYSTEM_MESSAGE_ERROR);
    }

    @Test
    void buildContentsIncludesToolCallAndResultMessages() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("key", "val");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("output", "done");

        List<Content> contents = client().buildContents(List.of(
                new TextAgentMessage(ChatRole.USER, "query"),
                new ToolCallAgentMessage("id-1", "myTool", args),
                new ToolResultAgentMessage("id-1", "myTool", result)
        ));

        assertThat(contents).hasSize(3);
        assertThat(contents.get(1).role()).contains("model");
        assertThat(contents.get(2).role()).contains("user");
    }

    // --- toAgentStepResult ---

    @Test
    void toAgentStepResultReturnsFinalTextForTextResponse() {
        String json = """
                {"candidates":[{"content":{"parts":[{"text":"hello world"}],"role":"model"}}]}
                """;
        GenerateContentResponse response = GenerateContentResponse.fromJson(json);

        AgentStepResult result = client().toAgentStepResult(response);

        assertThat(result).isInstanceOf(FinalText.class);
        assertThat(((FinalText) result).text()).isEqualTo("hello world");
    }

    @Test
    void toAgentStepResultReturnsToolCallRequestForFunctionCallResponse() {
        String json = """
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"myTool","args":{"k":"v"}}}],"role":"model"}}]}
                """;
        GenerateContentResponse response = GenerateContentResponse.fromJson(json);

        AgentStepResult result = client().toAgentStepResult(response);

        assertThat(result).isInstanceOf(ToolCallRequest.class);
        ToolCallRequest tcr = (ToolCallRequest) result;
        assertThat(tcr.toolName()).isEqualTo("myTool");
        assertThat(tcr.callId()).isNotBlank();
        assertThat(tcr.arguments().get("k").asText()).isEqualTo("v");
    }

    @Test
    void toAgentStepResultGeneratesUuidCallIdForFunctionCall() {
        String json = """
                {"candidates":[{"content":{"parts":[{"functionCall":{"name":"t","args":{}}}],"role":"model"}}]}
                """;
        GenerateContentResponse response = GenerateContentResponse.fromJson(json);

        ToolCallRequest first = (ToolCallRequest) client().toAgentStepResult(response);
        ToolCallRequest second = (ToolCallRequest) client().toAgentStepResult(response);

        assertThat(first.callId()).isNotEqualTo(second.callId());
    }

    // --- 503 retry ---

    @Test
    void chatWithToolsRetries503ThenSucceeds() {
        LlmProperties props = buildPropsWithRetry(1, 1L);
        AtomicInteger attempts = new AtomicInteger();
        String successJson = """
                {"candidates":[{"content":{"parts":[{"text":"ok"}],"role":"model"}}]}
                """;

        GeminiLlmClient retryClient = new GeminiLlmClient(null, props, MAPPER) {
            @Override
            GenerateContentResponse callModel(String model, List<Content> contents, GenerateContentConfig config) {
                if (attempts.incrementAndGet() == 1) {
                    throw new ServerException(503, "UNAVAILABLE", "Service unavailable");
                }
                return GenerateContentResponse.fromJson(successJson);
            }
        };

        AgentStepResult result = retryClient.chatWithTools(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")), List.of());

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(result).isInstanceOf(FinalText.class);
        assertThat(((FinalText) result).text()).isEqualTo("ok");
    }

    @Test
    void chatWithToolsDoesNotRetryNon503Errors() {
        LlmProperties props = buildPropsWithRetry(2, 1L);
        AtomicInteger attempts = new AtomicInteger();

        GeminiLlmClient retryClient = new GeminiLlmClient(null, props, MAPPER) {
            @Override
            GenerateContentResponse callModel(String model, List<Content> contents, GenerateContentConfig config) {
                attempts.incrementAndGet();
                throw new ServerException(500, "INTERNAL", "Server error");
            }
        };

        assertThatThrownBy(() -> retryClient.chatWithTools(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")), List.of()))
                .isInstanceOf(ServerException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    // --- compat facade via LlmClient.chat ---

    @Test
    void chatWithNoToolsRejectsSystemOnlyInput() {
        assertThatThrownBy(() -> client().buildContents(List.of(
                new TextAgentMessage(ChatRole.SYSTEM, "only system")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LlmClient.NON_SYSTEM_MESSAGE_ERROR);
    }

    // --- helpers ---

    private LlmProperties buildPropsWithRetry(int maxRetries, long initialBackoffMs) {
        LlmProperties props = new LlmProperties();
        LlmProperties.ProviderProperties gemini = new LlmProperties.ProviderProperties();
        LlmProperties.RetryProperties retry = new LlmProperties.RetryProperties();
        retry.setMaxRetries(maxRetries);
        retry.setInitialBackoffMs(initialBackoffMs);
        gemini.setRetry(retry);
        props.setGemini(gemini);
        return props;
    }
}
