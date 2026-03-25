package com.whut.map.map_service.client;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.whut.map.map_service.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    private final Client geminiClient;
    private final LlmProperties llmProperties;

    @Override
    public String generateText(String prompt) {
        GenerateContentResponse response = geminiClient.models.generateContent(
                llmProperties.getGemini().getModel(),
                Content.fromParts(Part.fromText(prompt)),
                null
        );

        if(response == null) {
            throw new IllegalStateException("Gemini response is null");
        }

        String text = response.text();
        if(text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response contains no text");
        }

        return text;
    }
}
