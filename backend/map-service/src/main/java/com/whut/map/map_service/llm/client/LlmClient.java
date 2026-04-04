package com.whut.map.map_service.llm.client;

import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;

import java.util.List;

public interface LlmClient {

    default String generateText(String prompt) {
        return chat(List.of(new LlmChatMessage(ChatRole.USER, prompt)));
    }

    String chat(List<LlmChatMessage> messages);
}
