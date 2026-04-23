package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.dto.ChatRole;
import java.util.Objects;

public record TextAgentMessage(ChatRole role, String content) implements AgentMessage {

    public TextAgentMessage {
        Objects.requireNonNull(role, "role must not be null");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
