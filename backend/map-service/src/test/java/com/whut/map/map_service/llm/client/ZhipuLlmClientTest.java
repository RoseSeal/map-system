package com.whut.map.map_service.llm.client;

import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import ai.z.openapi.service.model.ChatToolType;
import ai.z.openapi.service.model.Choice;
import ai.z.openapi.service.model.ToolCalls;
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
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZhipuLlmClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZhipuLlmClient client() {
        return new ZhipuLlmClient(null, new LlmProperties(), MAPPER);
    }

    private ObjectNode emptyObject() {
        return MAPPER.createObjectNode();
    }

    // --- toZhipuMessages ---

    @Test
    void toZhipuMessagesPreservesTextRoles() {
        List<ChatMessage> messages = client().toZhipuMessages(List.of(
                new TextAgentMessage(ChatRole.SYSTEM, "system"),
                new TextAgentMessage(ChatRole.USER, "hello"),
                new TextAgentMessage(ChatRole.ASSISTANT, "previous")
        ));

        assertThat(messages).extracting(ChatMessage::getRole)
                .containsExactly(
                        ChatMessageRole.SYSTEM.value(),
                        ChatMessageRole.USER.value(),
                        ChatMessageRole.ASSISTANT.value()
                );
        assertThat(messages).extracting(m -> String.valueOf(m.getContent()))
                .containsExactly("system", "hello", "previous");
    }

    @Test
    void toZhipuMessagesMapsToolCallAgentMessage() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("ship", "vessel-1");

        List<ChatMessage> messages = client().toZhipuMessages(List.of(
                new TextAgentMessage(ChatRole.USER, "query"),
                new ToolCallAgentMessage("call-1", "myTool", args)
        ));

        assertThat(messages).hasSize(2);
        ChatMessage toolCallMsg = messages.get(1);
        assertThat(toolCallMsg.getRole()).isEqualTo(ChatMessageRole.ASSISTANT.value());
        assertThat(toolCallMsg.getToolCalls()).hasSize(1);
        ToolCalls tc = toolCallMsg.getToolCalls().get(0);
        assertThat(tc.getId()).isEqualTo("call-1");
        assertThat(tc.getType()).isEqualTo(ChatToolType.FUNCTION.value());
        assertThat(tc.getFunction().getName()).isEqualTo("myTool");
        assertThat(tc.getFunction().getArguments().isTextual()).isTrue();
        assertThat(tc.getFunction().getArguments().asText()).isEqualTo("{\"ship\":\"vessel-1\"}");
    }

    @Test
    void toZhipuMessagesMapsToolResultAgentMessage() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("output", "done");

        List<ChatMessage> messages = client().toZhipuMessages(List.of(
                new TextAgentMessage(ChatRole.USER, "query"),
                new ToolResultAgentMessage("call-1", "myTool", result)
        ));

        assertThat(messages).hasSize(2);
        ChatMessage toolResultMsg = messages.get(1);
        assertThat(toolResultMsg.getRole()).isEqualTo("tool");
        assertThat(toolResultMsg.getToolCallId()).isEqualTo("call-1");
    }

    @Test
    void toZhipuMessagesRejectsEmptyMessageList() {
        assertThatThrownBy(() -> client().toZhipuMessages(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(LlmClient.NON_SYSTEM_MESSAGE_ERROR);
    }

    // --- buildRequest ---

    @Test
    void buildRequestIncludesToolsWhenProvided() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        List<ToolDefinition> tools = List.of(new ToolDefinition("myTool", "does something", schema));

        ChatCompletionCreateParams params = client().buildRequest(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")),
                tools
        );

        assertThat(params.getTools()).hasSize(1);
        assertThat(params.getTools().get(0).getFunction().getName()).isEqualTo("myTool");
        assertThat(params.getTools().get(0).getType()).isEqualTo(ChatToolType.FUNCTION.value());
        assertThat(params.getToolChoice()).isEqualTo("auto");
    }

    @Test
    void buildRequestOmitsToolsWhenListIsEmpty() {
        ChatCompletionCreateParams params = client().buildRequest(
                List.of(new TextAgentMessage(ChatRole.USER, "hello")),
                List.of()
        );

        assertThat(params.getTools()).isNullOrEmpty();
    }

    // --- toAgentStepResult ---

    @Test
    void toAgentStepResultReturnsFinalTextWhenNoToolCalls() {
        ChatCompletionResponse response = buildTextResponse("result text", null);

        AgentStepResult result = client().toAgentStepResult(response);

        assertThat(result).isInstanceOf(FinalText.class);
        assertThat(((FinalText) result).text()).isEqualTo("result text");
    }

    @Test
    void toAgentStepResultReturnsToolCallRequestWithNativeId() {
        ToolCalls tc = buildToolCall("native-id-1", "myTool", "{\"k\":\"v\"}");
        ChatCompletionResponse response = buildToolCallResponse(List.of(tc));

        AgentStepResult result = client().toAgentStepResult(response);

        assertThat(result).isInstanceOf(ToolCallRequest.class);
        ToolCallRequest tcr = (ToolCallRequest) result;
        assertThat(tcr.callId()).isEqualTo("native-id-1");
        assertThat(tcr.toolName()).isEqualTo("myTool");
        assertThat(tcr.arguments().get("k").asText()).isEqualTo("v");
    }

    @Test
    void toAgentStepResultParsesTextualFunctionArguments() {
        ToolCalls tc = buildToolCallTextArgs("native-id-1", "myTool", "{\"k\":\"v\"}");
        ChatCompletionResponse response = buildToolCallResponse(List.of(tc));

        ToolCallRequest result = (ToolCallRequest) client().toAgentStepResult(response);

        assertThat(result.callId()).isEqualTo("native-id-1");
        assertThat(result.toolName()).isEqualTo("myTool");
        assertThat(result.arguments().get("k").asText()).isEqualTo("v");
    }

    @Test
    void toAgentStepResultGeneratesUuidWhenNativeIdIsAbsent() {
        ToolCalls tc = buildToolCall(null, "myTool", "{}");
        ChatCompletionResponse response = buildToolCallResponse(List.of(tc));

        ToolCallRequest result = (ToolCallRequest) client().toAgentStepResult(response);

        assertThat(result.callId()).isNotBlank();
        assertThat(result.callId()).isNotEqualTo("null");
    }

    @Test
    void toAgentStepResultGeneratesUuidWhenNativeIdIsBlank() {
        ToolCalls tc = buildToolCall("  ", "myTool", "{}");
        ChatCompletionResponse response = buildToolCallResponse(List.of(tc));

        ToolCallRequest result = (ToolCallRequest) client().toAgentStepResult(response);

        assertThat(result.callId()).isNotBlank();
    }

    @Test
    void toAgentStepResultDefaultsToEmptyObjectWhenArgumentsIsNull() {
        ToolCalls tc = buildToolCallNullArgs("call-1", "get_risk_snapshot");
        ChatCompletionResponse response = buildToolCallResponse(List.of(tc));

        ToolCallRequest result = (ToolCallRequest) client().toAgentStepResult(response);

        assertThat(result.toolName()).isEqualTo("get_risk_snapshot");
        assertThat(result.arguments()).isNotNull();
        assertThat(result.arguments().size()).isZero();
    }

    // --- helpers ---

    private ChatCompletionResponse buildTextResponse(String text, List<ToolCalls> toolCalls) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(ChatMessageRole.ASSISTANT.value());
        msg.setContent(text);
        msg.setToolCalls(toolCalls);
        return wrapInResponse(msg);
    }

    private ChatCompletionResponse buildToolCallResponse(List<ToolCalls> toolCalls) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(ChatMessageRole.ASSISTANT.value());
        msg.setToolCalls(toolCalls);
        return wrapInResponse(msg);
    }

    private ChatCompletionResponse wrapInResponse(ChatMessage msg) {
        Choice choice = new Choice();
        choice.setMessage(msg);

        ai.z.openapi.service.model.ModelData data =
                ai.z.openapi.service.model.ModelData.builder()
                        .choices(List.of(choice))
                        .build();

        ChatCompletionResponse response = new ChatCompletionResponse();
        response.setCode(200);
        response.setSuccess(true);
        response.setData(data);
        return response;
    }

    private ToolCalls buildToolCallNullArgs(String id, String name) {
        ai.z.openapi.service.model.ChatFunctionCall fc =
                ai.z.openapi.service.model.ChatFunctionCall.builder()
                        .name(name)
                        .arguments(null)
                        .build();
        ToolCalls tc = new ToolCalls();
        tc.setId(id);
        tc.setType(ChatToolType.FUNCTION.value());
        tc.setFunction(fc);
        return tc;
    }

    private ToolCalls buildToolCall(String id, String name, String argsJson) {
        try {
            ai.z.openapi.service.model.ChatFunctionCall fc =
                    ai.z.openapi.service.model.ChatFunctionCall.builder()
                            .name(name)
                            .arguments(MAPPER.readTree(argsJson))
                            .build();
            ToolCalls tc = new ToolCalls();
            tc.setId(id);
            tc.setType(ChatToolType.FUNCTION.value());
            tc.setFunction(fc);
            return tc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ToolCalls buildToolCallTextArgs(String id, String name, String argsJson) {
        ai.z.openapi.service.model.ChatFunctionCall fc =
                ai.z.openapi.service.model.ChatFunctionCall.builder()
                        .name(name)
                        .arguments(MAPPER.getNodeFactory().textNode(argsJson))
                        .build();
        ToolCalls tc = new ToolCalls();
        tc.setId(id);
        tc.setType(ChatToolType.FUNCTION.value());
        tc.setFunction(fc);
        return tc;
    }
}
