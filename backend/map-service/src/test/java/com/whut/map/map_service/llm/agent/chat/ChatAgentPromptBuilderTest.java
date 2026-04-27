package com.whut.map.map_service.llm.agent.chat;

import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.TextAgentMessage;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.llm.service.ChatAgentMode;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ChatAgentPromptBuilderTest {

    private final ChatAgentPromptBuilder builder = new ChatAgentPromptBuilder(new PromptTemplateService());
    private final AgentSnapshot snapshot = new AgentSnapshot(1L, null, Map.of());

    @Test
    void buildReturnsTwoMessages() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "how far is target?", List.of("t1"), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        assertThat(messages).hasSize(2);
    }

    @Test
    void firstMessageIsSystemRole() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of("t1"), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        assertThat(messages.get(0)).isInstanceOf(TextAgentMessage.class);
        assertThat(((TextAgentMessage) messages.get(0)).role()).isEqualTo(ChatRole.SYSTEM);
    }

    @Test
    void secondMessageIsUserRoleContainingRequestContent() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "how fast is it?", List.of("t1"), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        TextAgentMessage userMsg = (TextAgentMessage) messages.get(1);
        assertThat(userMsg.role()).isEqualTo(ChatRole.USER);
        assertThat(userMsg.content()).contains("how fast is it?");
    }

    @Test
    void selectedTargetIdsAppearsInUserMessage() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "details?", List.of("target-A", "target-B"), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        String userContent = ((TextAgentMessage) messages.get(1)).content();
        assertThat(userContent).contains("target-A");
        assertThat(userContent).contains("target-B");
    }

    @Test
    void emptySelectedTargetIdsProducesContentWithSnapshotVersion() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "general question", List.of(), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        String userContent = ((TextAgentMessage) messages.get(1)).content();
        assertThat(userContent).startsWith("general question");
        assertThat(userContent).contains("1"); // snapshot version
        assertThat(userContent).doesNotContain("目标 ID");
    }

    @Test
    void nullSelectedTargetIdsProducesContentWithSnapshotVersion() {
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "general question", null, false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, snapshot);
        String userContent = ((TextAgentMessage) messages.get(1)).content();
        assertThat(userContent).startsWith("general question");
        assertThat(userContent).contains("1"); // snapshot version
        assertThat(userContent).doesNotContain("目标 ID");
    }

    @Test
    void snapshotVersionAppearsInUserMessage() {
        AgentSnapshot v42 = new AgentSnapshot(42L, null, Map.of());
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of(), false, ChatAgentMode.CHAT, null);
        List<AgentMessage> messages = builder.build(request, v42);
        String userContent = ((TextAgentMessage) messages.get(1)).content();
        assertThat(userContent).contains("42");
    }
}
