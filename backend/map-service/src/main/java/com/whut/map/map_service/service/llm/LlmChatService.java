package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import com.whut.map.map_service.service.llm.validation.ValidationResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
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
    private final PromptTemplateService promptTemplateService;
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

        List<LlmChatMessage> messages = buildMessages(request);
        CompletableFuture
                .supplyAsync(() -> llmClient.chat(messages), llmExecutor)
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

    private List<LlmChatMessage> buildMessages(ChatRequestPayload request) {
        return List.of(
                new LlmChatMessage(
                        ChatRole.SYSTEM,
                        promptTemplateService.getSystemPrompt(PromptScene.CHAT)
                ),
                new LlmChatMessage(ChatRole.USER, request.getContent())
        );
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
