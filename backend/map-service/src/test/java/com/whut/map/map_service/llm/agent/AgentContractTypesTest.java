package com.whut.map.map_service.llm.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentContractTypesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectNode emptyObject() {
        return MAPPER.createObjectNode();
    }

    // --- ToolDefinition ---

    @Test
    void toolDefinitionRejectsBlankName() {
        assertThatThrownBy(() -> new ToolDefinition("", "desc", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void toolDefinitionRejectsBlankDescription() {
        assertThatThrownBy(() -> new ToolDefinition("myTool", "  ", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    @Test
    void toolDefinitionRejectsNullSchema() {
        assertThatThrownBy(() -> new ToolDefinition("myTool", "desc", null))
                .isInstanceOf(NullPointerException.class);
    }

    // --- ToolCall ---

    @Test
    void toolCallRejectsBlankCallId() {
        assertThatThrownBy(() -> new ToolCall("", "myTool", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callId");
    }

    @Test
    void toolCallRejectsBlankToolName() {
        assertThatThrownBy(() -> new ToolCall("id-1", " ", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    // --- ToolResult ---

    @Test
    void toolResultRejectsBlankCallId() {
        assertThatThrownBy(() -> new ToolResult("", "myTool", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callId");
    }

    @Test
    void toolResultRejectsBlankToolName() {
        assertThatThrownBy(() -> new ToolResult("id-1", "", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    // --- TextAgentMessage ---

    @Test
    void textAgentMessageRejectsBlankContent() {
        assertThatThrownBy(() -> new TextAgentMessage(ChatRole.USER, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    @Test
    void textAgentMessageRejectsNullRole() {
        assertThatThrownBy(() -> new TextAgentMessage(null, "hello"))
                .isInstanceOf(NullPointerException.class);
    }

    // --- ToolCallAgentMessage ---

    @Test
    void toolCallAgentMessageRejectsNullArguments() {
        assertThatThrownBy(() -> new ToolCallAgentMessage("id-1", "myTool", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolCallAgentMessageRejectsBlankCallId() {
        assertThatThrownBy(() -> new ToolCallAgentMessage("", "myTool", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("callId");
    }

    // --- ToolResultAgentMessage ---

    @Test
    void toolResultAgentMessageRejectsNullResult() {
        assertThatThrownBy(() -> new ToolResultAgentMessage("id-1", "myTool", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toolResultAgentMessageRejectsBlankToolName() {
        assertThatThrownBy(() -> new ToolResultAgentMessage("id-1", "", emptyObject()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    // --- LlmClient chat compat facade ---

    @Test
    void chatCompatFacadeReturnsFinalTextFromChatWithTools() {
        FakeToolClient client = new FakeToolClient(new FinalText("hello from agent"));

        String result = client.chat(
                List.of(new com.whut.map.map_service.llm.dto.LlmChatMessage(ChatRole.USER, "ping")));

        assertThat(result).isEqualTo("hello from agent");
    }

    @Test
    void chatCompatFacadeThrowsWhenProviderReturnsToolCallWithNoTools() {
        FakeToolClient client = new FakeToolClient(
                new ToolCallRequest("id-1", "myTool", emptyObject()));

        assertThatThrownBy(() -> client.chat(
                List.of(new com.whut.map.map_service.llm.dto.LlmChatMessage(ChatRole.USER, "ping"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("myTool");
    }

    @Test
    void generateTextStillDelegatesToChat() {
        FakeToolClient client = new FakeToolClient(new FinalText("delegated"));

        String result = client.generateText("hello");

        assertThat(result).isEqualTo("delegated");
        assertThat(client.lastMessages).hasSize(1);
        assertThat(((TextAgentMessage) client.lastMessages.get(0)).role()).isEqualTo(ChatRole.USER);
        assertThat(((TextAgentMessage) client.lastMessages.get(0)).content()).isEqualTo("hello");
    }

    @Test
    void nonSystemMessageErrorConstantMatchesExpected() {
        assertThat(LlmClient.NON_SYSTEM_MESSAGE_ERROR)
                .isEqualTo("messages must contain at least one non-system message");
    }

    private static final class FakeToolClient implements LlmClient {
        private final AgentStepResult fixedResult;
        List<AgentMessage> lastMessages;

        FakeToolClient(AgentStepResult fixedResult) {
            this.fixedResult = fixedResult;
        }

        @Override
        public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            this.lastMessages = messages;
            return fixedResult;
        }
    }
}
