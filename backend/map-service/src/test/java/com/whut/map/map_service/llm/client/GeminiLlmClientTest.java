package com.whut.map.map_service.llm.client;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GeminiLlmClientTest {

    @Test
    void buildConfigMapsSystemInstruction() {
        GeminiLlmClient client = new GeminiLlmClient(null, new LlmProperties());

        GenerateContentConfig config = client.buildConfig(List.of(
                new LlmChatMessage(ChatRole.SYSTEM, "system prompt"),
                new LlmChatMessage(ChatRole.USER, "hello")
        ));

        assertThat(config).isNotNull();
        assertThat(config.systemInstruction()).isPresent();
        assertThat(config.systemInstruction().orElseThrow().parts()).isPresent();
        assertThat(config.systemInstruction().orElseThrow().parts().orElseThrow()).hasSize(1);
        assertThat(config.systemInstruction().orElseThrow().parts().orElseThrow().get(0).text()).contains("system prompt");
    }

    @Test
    void buildContentsKeepsConversationRoles() {
        GeminiLlmClient client = new GeminiLlmClient(null, new LlmProperties());

        List<Content> contents = client.buildContents(List.of(
                new LlmChatMessage(ChatRole.SYSTEM, "system prompt"),
                new LlmChatMessage(ChatRole.USER, "hello"),
                new LlmChatMessage(ChatRole.ASSISTANT, "previous reply"),
                new LlmChatMessage(ChatRole.USER, "follow up")
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
        GeminiLlmClient client = new GeminiLlmClient(null, new LlmProperties());

        assertThatThrownBy(() -> client.buildContents(List.of(
                new LlmChatMessage(ChatRole.SYSTEM, "system prompt")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(GeminiLlmClient.NON_SYSTEM_MESSAGE_ERROR);
    }
}
