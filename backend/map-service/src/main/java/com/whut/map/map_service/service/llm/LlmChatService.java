package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.websocket.ChatMessageFactory;
import com.whut.map.map_service.websocket.WebSocketService;
import com.whut.map.map_service.websocket.validation.ChatRequestValidator;
import com.whut.map.map_service.websocket.validation.ValidationResult;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

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
    private final WebSocketService webSocketService;
    private final ChatMessageFactory chatMessageFactory;
    private final ChatRequestValidator chatRequestValidator;
    private final ExecutorService llmExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void handleChat(WebSocketSession session, FrontendChatPayload request) {
        ValidationResult validationResult = chatRequestValidator.validateTextRequest(request);
        if (validationResult.hasError()) {
            webSocketService.sendToSession(session, validationResult.errorMessage());
            return;
        }

        if (!llmProperties.isEnabled()) {
            webSocketService.sendToSession(session, chatMessageFactory.buildErrorMessage(
                    request.getSequenceId(),
                    request.getMessageId(),
                    ChatErrorCode.LLM_DISABLED,
                    "LLM chat is disabled."
            ));
            return;
        }

        String prompt = buildPrompt(request);
        CompletableFuture
                .supplyAsync(() -> llmClient.generateText(prompt), llmExecutor)
                .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((responseText, throwable) -> {
                    if (throwable == null) {
                        webSocketService.sendToSession(session, chatMessageFactory.buildReplyMessage(
                                request,
                                responseText,
                                resolveProviderName()
                        ));
                        return;
                    }

                    Throwable cause = unwrap(throwable);
                    ChatErrorCode errorCode = cause instanceof TimeoutException
                            ? ChatErrorCode.LLM_TIMEOUT
                            : ChatErrorCode.LLM_REQUEST_FAILED;
                    String errorMessage = cause instanceof TimeoutException
                            ? "LLM request timed out."
                            : "LLM request failed.";

                    log.warn("LLM chat request failed for session {}, type={}, message={}",
                            session.getId(),
                            cause.getClass().getSimpleName(),
                            cause.getMessage());

                    webSocketService.sendToSession(session, chatMessageFactory.buildErrorMessage(
                            request.getSequenceId(),
                            request.getMessageId(),
                            errorCode,
                            errorMessage
                    ));
                });
    }

    private String buildPrompt(FrontendChatPayload request) {
        StringBuilder prompt = new StringBuilder("You are a maritime assistant. Answer the user's current message directly and concisely in 2-3 sentences.\n");
        if (request.getInputType() != null) {
            prompt.append("Input type: ").append(request.getInputType().name()).append('\n');
        }
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
