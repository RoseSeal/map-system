package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmExecutorConfig;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.context.RiskContextFormatter;
import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.memory.ConversationMemory;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class LlmChatService {

    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final PromptTemplateService promptTemplateService;
    private final RiskContextHolder riskContextHolder;
    private final RiskContextFormatter riskContextFormatter;
    private final ConversationMemory conversationMemory;
    @Qualifier(LlmExecutorConfig.LLM_EXECUTOR)
    private final ExecutorService llmExecutor;

    public record ChatReplyResult(String content, String provider) {
    }

    public void handleChat(
            LlmChatRequest request,
            Consumer<ChatReplyResult> onSuccess,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        if (!llmProperties.isEnabled()) {
            onError.accept(LlmErrorCode.LLM_DISABLED, "LLM chat is disabled.");
            return;
        }
        if (!StringUtils.hasText(request.content())) {
            onError.accept(LlmErrorCode.LLM_FAILED, "Chat content must not be blank.");
            return;
        }

        String conversationId = request.conversationId();

        ConversationMemory.ConversationPermit permit = conversationMemory.tryAcquire(conversationId);
        if (permit == null) {
            onError.accept(LlmErrorCode.CONVERSATION_BUSY,
                    "Previous request in this conversation is still processing.");
            return;
        }

        try {
            List<LlmChatMessage> messages = buildMessages(request);
            CompletableFuture<String> future = CompletableFuture
                    .supplyAsync(() -> llmClient.chat(messages), llmExecutor);
            future
                    .orTimeout(llmProperties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                    .whenComplete((responseText, throwable) -> {
                        try {
                            if (throwable == null) {
                                conversationMemory.append(conversationId,
                                        new LlmChatMessage(ChatRole.USER, request.content()));
                                conversationMemory.append(conversationId,
                                        new LlmChatMessage(ChatRole.ASSISTANT, responseText));
                                onSuccess.accept(new ChatReplyResult(responseText, resolveProviderName()));
                                return;
                            }

                            Throwable cause = unwrap(throwable);
                            if (cause instanceof TimeoutException) {
                                future.cancel(true);
                                log.warn("LLM chat request timed out after {} ms. Check DNS/proxy/network reachability.",
                                        llmProperties.getTimeoutMs());
                                onError.accept(LlmErrorCode.LLM_TIMEOUT, "LLM request timed out.");
                                return;
                            }

                            log.warn("LLM chat request failed, type={}, message={}",
                                    cause.getClass().getSimpleName(),
                                    cause.getMessage());
                            onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
                        } finally {
                            permit.close();
                        }
                    });
        } catch (RejectedExecutionException e) {
            permit.close();
            log.warn("LLM executor rejected chat request: {}", e.getMessage());
            onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
        } catch (RuntimeException e) {
            permit.close();
            throw e;
        }
    }

    private List<LlmChatMessage> buildMessages(LlmChatRequest request) {
        List<LlmChatMessage> messages = new ArrayList<>();
        messages.add(new LlmChatMessage(
                ChatRole.SYSTEM,
                promptTemplateService.getSystemPrompt(PromptScene.CHAT)
        ));

        String riskContext = resolveRiskContext(request);
        if (StringUtils.hasText(riskContext)) {
            messages.add(new LlmChatMessage(ChatRole.USER, riskContext));
        }

        int historyStartIndex = messages.size();
        List<LlmChatMessage> history = conversationMemory.getHistory(request.conversationId());
        messages.addAll(history);
        int historyEndIndex = messages.size();
        messages.add(new LlmChatMessage(ChatRole.USER, request.content()));

        return trimToTokenBudget(messages, historyStartIndex, historyEndIndex);
    }

    private String resolveRiskContext(LlmChatRequest request) {
        var context = riskContextHolder.getCurrent();
        var updatedAt = riskContextHolder.getUpdatedAt();
        return riskContextFormatter.formatConsolidated(context, request.selectedTargetIds(), updatedAt);
    }

    private String resolveProviderName() {
        return StringUtils.hasText(llmProperties.getProvider()) ? llmProperties.getProvider() : "llm";
    }

    private List<LlmChatMessage> trimToTokenBudget(
            List<LlmChatMessage> messages,
            int historyStartIndex,
            int historyEndIndex
    ) {
        int budgetChars = llmProperties.getConversationTokenBudget();
        if (budgetChars <= 0) {
            return messages;
        }

        int totalChars = countTotalChars(messages);
        if (totalChars <= budgetChars) {
            return messages;
        }

        List<LlmChatMessage> result = new ArrayList<>(messages);
        // History is expected to be written as USER/ASSISTANT pairs.
        // If corruption ever leaves an odd trailing history message, this loop intentionally
        // avoids trimming a single orphaned entry so we do not destroy role alignment further.
        if (((historyEndIndex - historyStartIndex) % 2) != 0) {
            log.warn("Conversation history length is not aligned to USER/ASSISTANT pairs, historySize={}",
                    historyEndIndex - historyStartIndex);
        }
        while (totalChars > budgetChars && historyStartIndex < historyEndIndex - 1) {
            totalChars -= result.get(historyStartIndex).content().length();
            totalChars -= result.get(historyStartIndex + 1).content().length();
            result.remove(historyStartIndex);
            result.remove(historyStartIndex);
            historyEndIndex -= 2;
        }

        if (totalChars > budgetChars) {
            log.warn("LLM chat request still exceeds conversation token budget after trimming history, totalChars={}, budgetChars={}",
                    totalChars, budgetChars);
        }

        return result;
    }

    private int countTotalChars(List<LlmChatMessage> messages) {
        return messages.stream().mapToInt(message -> message.content().length()).sum();
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }
}
