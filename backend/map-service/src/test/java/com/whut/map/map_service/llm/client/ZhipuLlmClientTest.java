package com.whut.map.map_service.llm.client;

import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZhipuLlmClientTest {

    @Test
    void toZhipuMessagesPreservesRoles() {
        ZhipuLlmClient client = new ZhipuLlmClient(null, new LlmProperties());

        List<ChatMessage> messages = client.toZhipuMessages(List.of(
                new LlmChatMessage(ChatRole.SYSTEM, "system"),
                new LlmChatMessage(ChatRole.USER, "hello"),
                new LlmChatMessage(ChatRole.ASSISTANT, "previous")
        ));

        assertThat(messages).extracting(ChatMessage::getRole)
                .containsExactly(
                        ChatMessageRole.SYSTEM.value(),
                        ChatMessageRole.USER.value(),
                        ChatMessageRole.ASSISTANT.value()
                );
        assertThat(messages).extracting(ChatMessage::getContent)
                .containsExactly("system", "hello", "previous");
    }

    @Test
    void toZhipuMessagesRejectsEmptyMessageList() {
        ZhipuLlmClient client = new ZhipuLlmClient(null, new LlmProperties());

        assertThatThrownBy(() -> client.toZhipuMessages(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(GeminiLlmClient.NON_SYSTEM_MESSAGE_ERROR);
    }
}
