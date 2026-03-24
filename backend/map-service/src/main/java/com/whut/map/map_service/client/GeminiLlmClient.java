package com.whut.map.map_service.client;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.whut.map.map_service.config.LlmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GeminiLlmClient implements LlmClient {

    private final Client geminiClient;
    private final LlmProperties llmProperties;

    @Override
    public String generateText(String prompt) {
        GenerateContentResponse response = geminiClient.models.generateContent(
                llmProperties.getModel(),
                Content.fromParts(Part.fromText(prompt)),
                null
        );
        return response.text();
    }
}
