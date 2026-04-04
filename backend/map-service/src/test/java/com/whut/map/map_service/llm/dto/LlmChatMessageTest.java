package com.whut.map.map_service.llm.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmChatMessageTest {

    @Test
    void rejectsNullRole() {
        assertThatThrownBy(() -> new LlmChatMessage(null, "hello"))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("role must not be null");
    }

    @Test
    void rejectsBlankContent() {
        assertThatThrownBy(() -> new LlmChatMessage(ChatRole.USER, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("content must not be blank");
    }
}
