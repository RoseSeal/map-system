package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import com.whut.map.map_service.service.llm.validation.ValidationResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatService {

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final ChatPayloadValidator chatPayloadValidator;
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public record ChatReplyResult(String content, String provider) {
    }

    public void handleChat(
            ChatRequestPayload request,
            Consumer<ChatReplyResult> onSuccess,
            BiConsumer<ChatErrorCode, String> onError
    ) {
        ValidationResult validationResult = chatPayloadValidator.validateTextRequest(request);
        if (validationResult.hasError()) {
            onError.accept(validationResult.errorCode(), validationResult.errorMessage());
            return;
        }

        if (!llmProperties.isEnabled()) {
            onError.accept(ChatErrorCode.LLM_DISABLED, "LLM chat is disabled.");
            return;
        }

        String prompt = buildPrompt(request);
        CompletableFuture
                .supplyAsync(() -> llmClient.generateText(prompt), llmExecutor)
                .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((responseText, throwable) -> {
                    if (throwable == null) {
                        onSuccess.accept(new ChatReplyResult(responseText, resolveProviderName()));
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    ChatErrorCode errorCode = cause instanceof TimeoutException
                            ? ChatErrorCode.LLM_TIMEOUT
                            : ChatErrorCode.LLM_REQUEST_FAILED;
                    String errorMessage = cause instanceof TimeoutException
                            ? "LLM request timed out."
                            : "LLM request failed.";

                    log.warn("LLM chat request failed, type={}, message={}",
                            cause.getClass().getSimpleName(),
                            cause.getMessage());

                    onError.accept(errorCode, errorMessage);
                });
    }

    private String buildPrompt(ChatRequestPayload request) {
        StringBuilder prompt = new StringBuilder("You are a maritime assistant. Answer the user's current message directly and concisely in 2-3 sentences.\n");
        prompt.append("User message:\n").append(request.getContent());
        return prompt.toString();
    }

    private String resolveProviderName() {
        return StringUtils.hasText(llmProperties.getProvider()) ? llmProperties.getProvider() : "llm";
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    @PreDestroy
    public void shutdownExecutor() {
        llmExecutor.shutdownNow();
    }
}
