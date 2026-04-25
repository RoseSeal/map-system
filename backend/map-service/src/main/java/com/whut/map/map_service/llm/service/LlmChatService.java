package com.whut.map.map_service.llm.service;

import com.google.genai.errors.ApiException;
import com.whut.map.map_service.llm.agent.AgentLoopOrchestrator;
import com.whut.map.map_service.llm.agent.AgentLoopResult;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.agent.AgentStepEvent;
import com.whut.map.map_service.llm.agent.AgentStepSink;
import com.whut.map.map_service.llm.agent.chat.ChatAgentPromptBuilder;
import com.whut.map.map_service.llm.config.LlmExecutorConfig;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.context.ExplanationCache;
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
    private final ExplanationCache explanationCache;
    private final ConversationMemory conversationMemory;
    @Qualifier(LlmExecutorConfig.LLM_EXECUTOR)
    private final ExecutorService llmExecutor;
    private final AgentSnapshotFactory agentSnapshotFactory;
    private final AgentLoopOrchestrator agentLoopOrchestrator;
    private final ChatAgentPromptBuilder chatAgentPromptBuilder;

    public record ChatReplyResult(String content, String provider) {
    }

    public void handleChat(
            LlmChatRequest request,
            Consumer<ChatReplyResult> onSuccess,
            BiConsumer<LlmErrorCode, String> onError
    ) {
        handleChat(request, onSuccess, onError, AgentStepSink.NOOP);
    }

    public void handleChat(
            LlmChatRequest request,
            Consumer<ChatReplyResult> onSuccess,
            BiConsumer<LlmErrorCode, String> onError,
            AgentStepSink onStep
    ) {
        if (!llmProperties.isEnabled()) {
            onError.accept(LlmErrorCode.LLM_DISABLED, "LLM chat is disabled.");
            return;
        }
        if (!StringUtils.hasText(request.content())) {
            onError.accept(LlmErrorCode.LLM_FAILED, "Chat content must not be blank.");
            return;
        }

        if (request.agentMode() == ChatAgentMode.AGENT && !llmProperties.isAgentModeEnabled()) {
            onError.accept(LlmErrorCode.LLM_DISABLED, "Agent mode is not enabled.");
            return;
        }

        String conversationId = request.conversationId();

        ConversationMemory.ConversationPermit permit = conversationMemory.tryAcquire(conversationId);
        if (permit == null) {
            onError.accept(LlmErrorCode.CONVERSATION_BUSY,
                    "Previous request in this conversation is still processing.");
            return;
        }

        boolean useAgentPath = request.agentMode() == ChatAgentMode.AGENT
                && llmProperties.isAgentModeEnabled();

        if (useAgentPath) {
            runAgentChat(request, permit, onSuccess, onError, onStep);
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
                                LlmChatMessage userMessage = new LlmChatMessage(ChatRole.USER, request.content());
                                LlmChatMessage assistantMessage = new LlmChatMessage(ChatRole.ASSISTANT, responseText);
                                if (request.editLastUserMessage()) {
                                    boolean replaced = conversationMemory.replaceLastTurn(
                                            conversationId,
                                            userMessage,
                                            assistantMessage
                                    );
                                    if (!replaced) {
                                        log.warn("Conversation edit fallback to append due to missing last turn, conversationId={}",
                                                conversationId);
                                        conversationMemory.append(conversationId, userMessage);
                                        conversationMemory.append(conversationId, assistantMessage);
                                    }
                                } else {
                                    conversationMemory.append(conversationId, userMessage);
                                    conversationMemory.append(conversationId, assistantMessage);
                                }
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

                            if (cause instanceof ApiException apiException && apiException.code() == 503) {
                                log.warn("LLM chat request hit temporary upstream overload, provider={}, status={}, message={}",
                                        resolveProviderName(),
                                        apiException.status(),
                                        apiException.message());
                                onError.accept(LlmErrorCode.LLM_FAILED,
                                        "LLM service is temporarily unavailable due to high demand. Please retry shortly.");
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
        if (request.editLastUserMessage() && conversationMemory.peekLastTurn(request.conversationId()) != null && history.size() >= 2) {
            history = history.subList(0, history.size() - 2);
        }
        messages.addAll(history);
        int historyEndIndex = messages.size();
        messages.add(new LlmChatMessage(ChatRole.USER, request.content()));

        return trimToTokenBudget(messages, historyStartIndex, historyEndIndex);
    }

    private String resolveRiskContext(LlmChatRequest request) {
        var context = riskContextHolder.getCurrent();
        var updatedAt = riskContextHolder.getUpdatedAt();
        return riskContextFormatter.formatConsolidated(context, request.selectedTargetIds(), updatedAt, explanationCache);
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

    private void runAgentChat(
            LlmChatRequest request,
            ConversationMemory.ConversationPermit permit,
            Consumer<ChatReplyResult> onSuccess,
            BiConsumer<LlmErrorCode, String> onError,
            AgentStepSink onStep
    ) {
        AgentSnapshot snapshot;
        try {
            snapshot = agentSnapshotFactory.build();
        } catch (IllegalStateException e) {
            permit.close();
            log.warn("Agent chat: no risk context snapshot available: {}", e.getMessage());
            onError.accept(LlmErrorCode.LLM_FAILED, "Agent mode: no risk context snapshot available.");
            return;
        }

        CompletableFuture<AgentLoopResult> future;
        try {
            var initialMessages = chatAgentPromptBuilder.build(request, snapshot);
            int maxIterations = llmProperties.getAdvisory().getMaxIterations();
            future = CompletableFuture.supplyAsync(
                    () -> agentLoopOrchestrator.run(snapshot, initialMessages, maxIterations, onStep),
                    llmExecutor
            );
        } catch (RejectedExecutionException e) {
            permit.close();
            log.warn("LLM executor rejected agent chat request: {}", e.getMessage());
            onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
            return;
        } catch (RuntimeException e) {
            permit.close();
            log.warn("Agent chat request failed before async execution: type={}, message={}",
                    e.getClass().getSimpleName(), e.getMessage());
            onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
            return;
        }

        future.orTimeout(llmProperties.getAgentChatTimeoutMs(), TimeUnit.MILLISECONDS)
                .whenComplete((loopResult, throwable) -> {
                    try {
                        if (throwable != null) {
                            Throwable cause = unwrap(throwable);
                            if (cause instanceof TimeoutException) {
                                future.cancel(true);
                                log.warn("Agent chat request timed out after {} ms", llmProperties.getAgentChatTimeoutMs());
                                onError.accept(LlmErrorCode.LLM_TIMEOUT, "LLM request timed out.");
                            } else {
                                log.warn("Agent chat request failed: type={}, message={}",
                                        cause.getClass().getSimpleName(), cause.getMessage());
                                onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
                            }
                            return;
                        }
                        switch (loopResult) {
                            case AgentLoopResult.Completed completed -> {
                                LlmChatMessage userMessage = new LlmChatMessage(ChatRole.USER, request.content());
                                LlmChatMessage assistantMessage = new LlmChatMessage(ChatRole.ASSISTANT, completed.finalText());
                                if (request.editLastUserMessage()) {
                                    boolean replaced = conversationMemory.replaceLastTurn(
                                            request.conversationId(), userMessage, assistantMessage);
                                    if (!replaced) {
                                        log.warn("Agent chat edit fallback to append, conversationId={}",
                                                request.conversationId());
                                        conversationMemory.append(request.conversationId(), userMessage);
                                        conversationMemory.append(request.conversationId(), assistantMessage);
                                    }
                                } else {
                                    conversationMemory.append(request.conversationId(), userMessage);
                                    conversationMemory.append(request.conversationId(), assistantMessage);
                                }
                                onSuccess.accept(new ChatReplyResult(completed.finalText(), resolveProviderName()));
                            }
                            case AgentLoopResult.MaxIterationsExceeded exceeded -> {
                                log.warn("Agent chat loop exceeded max iterations ({})", exceeded.iterations());
                                onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
                            }
                            case AgentLoopResult.ProviderFailed failed -> {
                                log.warn("Agent chat provider failed: {} - {}", failed.errorCode(), failed.message());
                                if (failed.cause() instanceof ApiException apiException && apiException.code() == 503) {
                                    log.warn("Agent chat provider temporarily overloaded, status={}", apiException.status());
                                    onError.accept(LlmErrorCode.LLM_FAILED,
                                            "LLM service is temporarily unavailable due to high demand. Please retry shortly.");
                                } else {
                                    onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
                                }
                            }
                            case AgentLoopResult.ToolFailed failed -> {
                                log.warn("Agent chat tool {} failed: {}", failed.toolName(), failed.message());
                                onError.accept(LlmErrorCode.LLM_FAILED, "LLM request failed.");
                            }
                        }
                    } finally {
                        permit.close();
                    }
                });
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
