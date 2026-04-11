package com.whut.map.map_service.llm.client;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "zhipu")
public class ZhipuLlmClient implements LlmClient {

    private final ZhipuAiClient zhipuAiClient;
    private final LlmProperties llmProperties;

    @Override
    public String chat(List<LlmChatMessage> messages) {
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(llmProperties.getZhipu().getModel())
                .messages(toZhipuMessages(messages))
                .build();

        ChatCompletionResponse response = zhipuAiClient.chat().createChatCompletion(request);

        if (response == null) {
            throw new IllegalStateException("Zhipu response is null");
        }

        if (!response.isSuccess()) {
            throw new IllegalStateException("Zhipu request failed: " + response.getMsg());
        }

        if (response.getData() == null
                || response.getData().getChoices() == null
                || response.getData().getChoices().isEmpty()
                || response.getData().getChoices().get(0).getMessage() == null) {
            throw new IllegalStateException("Zhipu response contains no message");
        }

        Object content = response.getData().getChoices().get(0).getMessage().getContent();
        if (content == null) {
            throw new IllegalStateException("Zhipu response contains no text");
        }

        String text = String.valueOf(content);
        if (text.isBlank() || "null".equalsIgnoreCase(text)) {
            throw new IllegalStateException("Zhipu response contains no text");
        }

        return text;
    }

    List<ChatMessage> toZhipuMessages(List<LlmChatMessage> messages) {
        List<ChatMessage> zhipuMessages = messages == null ? List.of() : messages.stream()
                .filter(message -> message != null)
                .map(message -> ChatMessage.builder()
                        .role(toZhipuRole(message.role()).value())
                        .content(message.content())
                        .build())
                .toList();

        if (zhipuMessages.isEmpty()) {
            throw new IllegalArgumentException(GeminiLlmClient.NON_SYSTEM_MESSAGE_ERROR);
        }

        return zhipuMessages;
    }

    private ChatMessageRole toZhipuRole(ChatRole role) {
        return switch (role) {
            case SYSTEM -> ChatMessageRole.SYSTEM;
            case USER -> ChatMessageRole.USER;
            case ASSISTANT -> ChatMessageRole.ASSISTANT;
        };
    }
}
