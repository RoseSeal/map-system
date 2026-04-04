package com.whut.map.map_service.llm.client;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    static final String NON_SYSTEM_MESSAGE_ERROR = "messages must contain at least one non-system message";

    private final Client geminiClient;
    private final LlmProperties llmProperties;

    @Override
    public String chat(List<LlmChatMessage> messages) {
        GenerateContentResponse response = geminiClient.models.generateContent(
                llmProperties.getGemini().getModel(),
                buildContents(messages),
                buildConfig(messages)
        );

        if (response == null) {
            throw new IllegalStateException("Gemini response is null");
        }

        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response contains no text");
        }

        return text;
    }

    List<Content> buildContents(List<LlmChatMessage> messages) {
        List<Content> contents = messages == null ? List.of() : messages.stream()
                .filter(message -> message != null)
                .filter(message -> message.role() != ChatRole.SYSTEM)
                .map(this::toGeminiContent)
                .toList();

        if (contents.isEmpty()) {
            throw new IllegalArgumentException(NON_SYSTEM_MESSAGE_ERROR);
        }

        return contents;
    }

    GenerateContentConfig buildConfig(List<LlmChatMessage> messages) {
        // System instruction is optional; chat() still requires at least one non-system message via buildContents().
        String systemInstruction = messages == null ? null : messages.stream()
                .filter(message -> message != null && message.role() == ChatRole.SYSTEM)
                .map(LlmChatMessage::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse(null);

        if (systemInstruction == null || systemInstruction.isBlank()) {
            return null;
        }

        return GenerateContentConfig.builder()
                .systemInstruction(Content.fromParts(Part.fromText(systemInstruction)))
                .build();
    }

    private Content toGeminiContent(LlmChatMessage message) {
        return Content.builder()
                .role(toGeminiRole(message.role()))
                .parts(List.of(Part.fromText(message.content())))
                .build();
    }

    private String toGeminiRole(ChatRole role) {
        return role == ChatRole.ASSISTANT ? "model" : "user";
    }
}
