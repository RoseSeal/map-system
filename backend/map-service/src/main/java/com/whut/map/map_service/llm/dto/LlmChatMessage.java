package com.whut.map.map_service.llm.dto;

import java.util.Objects;

public record LlmChatMessage(ChatRole role, String content) {

    public LlmChatMessage {
        Objects.requireNonNull(role, "role must not be null");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
