package com.whut.map.map_service.client;

import ai.z.openapi.ZhipuAiClient;
import ai.z.openapi.service.model.ChatCompletionCreateParams;
import ai.z.openapi.service.model.ChatCompletionResponse;
import ai.z.openapi.service.model.ChatMessage;
import ai.z.openapi.service.model.ChatMessageRole;
import com.whut.map.map_service.config.LlmProperties;
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
    public String generateText(String prompt) {
        ChatCompletionCreateParams request = ChatCompletionCreateParams.builder()
                .model(llmProperties.getZhipu().getModel())
                .messages(List.of(
                        ChatMessage.builder()
                                .role(ChatMessageRole.USER.value())
                                .content(prompt)
                                .build()
                ))
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
}


