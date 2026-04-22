package com.whut.map.map_service.llm.client;

import com.google.genai.Client;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "llm.provider", havingValue = "gemini")
public class GeminiLlmClient implements LlmClient {

    static final String NON_SYSTEM_MESSAGE_ERROR = "messages must contain at least one non-system message";

    private final Client geminiClient;
    private final LlmProperties llmProperties;

    @Override
    public String chat(List<LlmChatMessage> messages) {
        List<Content> contents = buildContents(messages);
        GenerateContentConfig config = buildConfig(messages);
        LlmProperties.RetryProperties retryProperties = llmProperties.getGemini().getRetry();
        int maxRetries = retryProperties == null ? 0 : retryProperties.getMaxRetries();
        long backoffMs = retryProperties == null ? 1000L : retryProperties.getInitialBackoffMs();

        for (int retryIndex = 0; ; retryIndex++) {
            try {
                GenerateContentResponse response = geminiClient.models.generateContent(
                        llmProperties.getGemini().getModel(),
                        contents,
                        config
                );
                return extractText(response);
            } catch (ServerException e) {
                if (!shouldRetry503(e, retryIndex, maxRetries)) {
                    throw e;
                }

                long delayMs = Math.max(1000L, backoffMs);
                log.warn("Gemini request got 503 UNAVAILABLE on attempt {} of {}. Retrying after {} ms.",
                        retryIndex + 1,
                        maxRetries + 1,
                        delayMs);
                sleepBackoff(delayMs);
                backoffMs = nextBackoff(delayMs);
            }
        }
    }

    private String extractText(GenerateContentResponse response) {
        if (response == null) {
            throw new IllegalStateException("Gemini response is null");
        }
        String text = response.text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Gemini response contains no text");
        }
        return text;
    }

    private boolean shouldRetry503(ServerException exception, int retryIndex, int maxRetries) {
        return exception.code() == 503 && retryIndex < maxRetries;
    }

    private long nextBackoff(long currentBackoffMs) {
        return Math.min(currentBackoffMs * 2L, 4000L);
    }

    private void sleepBackoff(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Gemini retry backoff was interrupted", e);
        }
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
