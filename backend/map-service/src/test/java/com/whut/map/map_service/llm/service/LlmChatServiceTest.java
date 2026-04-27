package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.agent.AgentLoopOrchestrator;
import com.whut.map.map_service.llm.agent.AgentLoopResult;
import com.whut.map.map_service.llm.agent.AgentStepEvent;
import com.whut.map.map_service.llm.agent.AgentStepStatus;
import com.whut.map.map_service.llm.service.ChatAgentMode;
import com.whut.map.map_service.llm.agent.AgentMessage;
import com.whut.map.map_service.llm.agent.AgentSnapshot;
import com.whut.map.map_service.llm.agent.AgentSnapshotFactory;
import com.whut.map.map_service.llm.agent.AgentStepResult;
import com.whut.map.map_service.llm.agent.ToolDefinition;
import com.whut.map.map_service.llm.agent.chat.ChatAgentPromptBuilder;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.shared.domain.RiskLevel;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.context.ExplanationCache;
import com.whut.map.map_service.llm.context.RiskContextFormatter;
import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.memory.ConversationMemory;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmChatServiceTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService();
    private final RiskContextHolder riskContextHolder = new RiskContextHolder();
    private final RiskContextFormatter riskContextFormatter = new RiskContextFormatter(new LlmProperties());

    @Test
    void validChatRequestsProduceChatReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, conversationMemory, executor, null, null, null);
            LlmChatRequest request = buildRequest();

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            callback.await();
            assertThat(llmClient.lastMessages).hasSize(2);
            assertThat(llmClient.lastMessages).containsExactly(
                    new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)),
                    new LlmChatMessage(ChatRole.USER, request.content())
            );
            assertThat(callback.reply()).isNotNull();
            assertThat(callback.reply().provider()).isEqualTo("gemini");
            assertThat(callback.reply().content()).isEqualTo("assistant reply");
            assertThat(callback.errorCode()).isNull();
            assertThat(conversationMemory.getHistory(request.conversationId())).containsExactly(
                    new LlmChatMessage(ChatRole.USER, request.content()),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant reply")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disabledChatRequestsReturnLlmDisabledError() {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setEnabled(false);
        StubLlmClient llmClient = new StubLlmClient();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor, null, null, null);
            LlmChatRequest request = buildRequest();

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            assertThat(callback.reply()).isNull();
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_DISABLED);
            assertThat(callback.errorMessage()).isEqualTo("LLM chat is disabled.");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void blankChatContentReturnsLlmFailedError() {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor, null, null, null);
            LlmChatRequest request = new LlmChatRequest("conversation-1", "event-1", "   ", List.of(), false, ChatAgentMode.CHAT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            assertThat(callback.reply()).isNull();
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(callback.errorMessage()).isEqualTo("Chat content must not be blank.");
            assertThat(llmClient.lastMessages).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void llmFailuresReturnChatError() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.failure = new IllegalStateException("boom");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor, null, null, null);
            LlmChatRequest request = buildRequest();

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            callback.await();
            assertThat(callback.reply()).isNull();
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(callback.errorMessage()).isEqualTo("LLM request failed.");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void generateTextDelegatesToSingleUserMessage() {
        RecordingLlmClient client = new RecordingLlmClient();

        String response = client.generateText("hello");

        assertThat(response).isEqualTo("delegated");
        assertThat(client.lastMessages()).containsExactly(new LlmChatMessage(ChatRole.USER, "hello"));
    }

    @Test
    void validChatRequestsInjectRiskSummaryWhenContextExists() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setChatContextMaxTargets(5);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        RiskContextHolder holder = new RiskContextHolder();
        holder.update(1L, com.whut.map.map_service.llm.dto.LlmRiskContext.builder()
                .ownShip(com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext.builder()
                        .id("own-1")
                        .longitude(120.1234)
                        .latitude(30.5678)
                        .sog(12.3)
                        .cog(87.6)
                        .build())
                .targets(List.of(com.whut.map.map_service.llm.dto.LlmRiskTargetContext.builder()
                        .targetId("target-1")
                        .riskLevel(RiskLevel.WARNING)
                        .currentDistanceNm(0.80)
                        .dcpaNm(0.42)
                        .tcpaSec(240)
                        .approaching(true)
                        .build()))
                .build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    holder,
                    new RiskContextFormatter(properties),
                    new ExplanationCache(),
                    new ConversationMemory(properties),
                    executor,
                    null, null, null
            );
            LlmChatRequest request = buildRequest();

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            callback.await();
            assertThat(llmClient.lastMessages).hasSize(3);
            assertThat(llmClient.lastMessages.get(0))
                    .isEqualTo(new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)));
            assertThat(llmClient.lastMessages.get(1).role()).isEqualTo(ChatRole.USER);
            assertThat(llmClient.lastMessages.get(1).content())
                    .contains("【当前态势】更新时间:")
                    .contains("目标船 target-1: 风险等级 WARNING");
            assertThat(llmClient.lastMessages.get(2))
                    .isEqualTo(new LlmChatMessage(ChatRole.USER, request.content()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void validChatRequestsInjectExplicitZeroTargetSummaryWhenNoTargetsExist() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        RiskContextHolder holder = new RiskContextHolder();
        holder.update(1L, com.whut.map.map_service.llm.dto.LlmRiskContext.builder()
                .ownShip(com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext.builder()
                        .id("own-1")
                        .longitude(120.1234)
                        .latitude(30.5678)
                        .sog(12.3)
                        .cog(87.6)
                        .build())
                .targets(List.of())
                .build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    holder,
                    new RiskContextFormatter(properties),
                    new ExplanationCache(),
                    new ConversationMemory(properties),
                    executor,
                    null, null, null
            );
            LlmChatRequest request = buildRequest();

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            callback.await();
            assertThat(llmClient.lastMessages).hasSize(3);
            assertThat(llmClient.lastMessages.get(1).content())
                    .contains("当前未追踪到目标船。")
                    .contains("共追踪 0 艘目标船，当前注入 0 艘，未注入 0 艘。");
            assertThat(llmClient.lastMessages.get(2))
                    .isEqualTo(new LlmChatMessage(ChatRole.USER, request.content()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selectedTargetIdsInjectConsolidatedRiskContext() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setChatContextMaxTargets(1);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "target detail reply";
        RiskContextHolder holder = new RiskContextHolder();
        holder.update(1L, com.whut.map.map_service.llm.dto.LlmRiskContext.builder()
                .ownShip(com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext.builder()
                        .id("own-1").longitude(120.0).latitude(30.0).sog(10.0).cog(90.0).build())
                .targets(List.of(
                        com.whut.map.map_service.llm.dto.LlmRiskTargetContext.builder()
                                .targetId("target-2")
                                .riskLevel(RiskLevel.WARNING)
                                .currentDistanceNm(0.8).dcpaNm(0.4).tcpaSec(240)
                                .approaching(true).longitude(120.5).latitude(30.5)
                                .speedKn(9.0).courseDeg(45.0)
                                .build(),
                        com.whut.map.map_service.llm.dto.LlmRiskTargetContext.builder()
                                .targetId("target-1")
                                .riskLevel(RiskLevel.SAFE)
                                .currentDistanceNm(5.0).dcpaNm(3.0).tcpaSec(600)
                                .approaching(false).longitude(121.0).latitude(31.0)
                                .speedKn(8.0).courseDeg(180.0)
                                .build()))
                .build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient, properties, promptTemplateService, holder,
                    new RiskContextFormatter(properties), new ExplanationCache(), new ConversationMemory(properties), executor,
                    null, null, null);
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "它距离多少", List.of("target-1"), false, ChatAgentMode.CHAT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(llmClient.lastMessages).hasSize(3);
            assertThat(llmClient.lastMessages.get(1).content())
                    .contains("【当前态势】")
                    .contains("目标船 target-2: 风险等级 WARNING")
                    .contains("【用户关注目标】")
                    .contains("目标船 target-1: 风险等级 SAFE")
                    .contains("位置: (121.0000, 31.0000)")
                    .contains("航速: 8.0节")
                    .contains("共追踪 2 艘目标船，当前注入 2 艘，未注入 0 艘。");
            assertThat(llmClient.lastMessages.get(2))
                    .isEqualTo(new LlmChatMessage(ChatRole.USER, "它距离多少"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selectedTargetIdsStillInjectSummaryWhenNoMatch() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setChatContextMaxTargets(5);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "fallback reply";
        RiskContextHolder holder = new RiskContextHolder();
        holder.update(1L, com.whut.map.map_service.llm.dto.LlmRiskContext.builder()
                .ownShip(com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext.builder()
                        .id("own-1").longitude(120.0).latitude(30.0).sog(10.0).cog(90.0).build())
                .targets(List.of(
                        com.whut.map.map_service.llm.dto.LlmRiskTargetContext.builder()
                                .targetId("target-1")
                                .riskLevel(RiskLevel.WARNING)
                                .currentDistanceNm(1.0).dcpaNm(0.5).tcpaSec(200)
                                .approaching(true)
                                .build()))
                .build());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient, properties, promptTemplateService, holder,
                    new RiskContextFormatter(properties), new ExplanationCache(), new ConversationMemory(properties), executor,
                    null, null, null);
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of("nonexistent"), false, ChatAgentMode.CHAT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(llmClient.lastMessages.get(1).content())
                    .contains("【当前态势】")
                    .contains("目标船 target-1: 风险等级 WARNING")
                    .contains("共追踪 1 艘目标船，当前注入 1 艘，未注入 0 艘。");
            assertThat(llmClient.lastMessages.get(2))
                    .isEqualTo(new LlmChatMessage(ChatRole.USER, "hello"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void editLastUserMessageReplacesLastTurnAndBuildsPromptWithoutPreviousTurn() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant-2-edited";
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ConversationMemory.ConversationPermit permit = conversationMemory.tryAcquire("conversation-1");
            permit.close();
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.USER, "hello"));
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1"));
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.USER, "follow up"));
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.ASSISTANT, "assistant-2"));

            LlmChatService service = buildService(llmClient, properties, conversationMemory, executor, null, null, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(
                    new LlmChatRequest("conversation-1", "edit-1", "edited follow up", null, true, ChatAgentMode.CHAT, null),
                    callback::captureReply,
                    callback::captureError
            );

            callback.await();
            assertThat(llmClient.lastMessages).containsExactly(
                    new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)),
                    new LlmChatMessage(ChatRole.USER, "hello"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1"),
                    new LlmChatMessage(ChatRole.USER, "edited follow up")
            );
            assertThat(conversationMemory.getHistory("conversation-1")).containsExactly(
                    new LlmChatMessage(ChatRole.USER, "hello"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1"),
                    new LlmChatMessage(ChatRole.USER, "edited follow up"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant-2-edited")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void editLastUserMessageFailureKeepsExistingConversationTurn() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.failure = new IllegalStateException("boom");
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ConversationMemory.ConversationPermit permit = conversationMemory.tryAcquire("conversation-1");
            permit.close();
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.USER, "hello"));
            conversationMemory.append("conversation-1", new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1"));

            LlmChatService service = buildService(llmClient, properties, conversationMemory, executor, null, null, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(
                    new LlmChatRequest("conversation-1", "edit-1", "edited hello", null, true, ChatAgentMode.CHAT, null),
                    callback::captureReply,
                    callback::captureError
            );

            callback.await();
            assertThat(callback.reply()).isNull();
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(conversationMemory.getHistory("conversation-1")).containsExactly(
                    new LlmChatMessage(ChatRole.USER, "hello"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void laterRequestsIncludeConversationHistory() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        StubLlmClient llmClient = new StubLlmClient();
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, conversationMemory, executor, null, null, null);

            LlmChatRequest first = buildRequest();
            llmClient.response = "assistant-1";
            CapturingChatCallback firstCallback = new CapturingChatCallback();
            service.handleChat(first, firstCallback::captureReply, firstCallback::captureError);
            firstCallback.await();

            LlmChatRequest second = new LlmChatRequest(
                    first.conversationId(),
                    "user-2",
                    "follow up",
                    null,
                    false,
                    ChatAgentMode.CHAT,
                    null
            );
            llmClient.response = "assistant-2";
            CapturingChatCallback secondCallback = new CapturingChatCallback();
            service.handleChat(second, secondCallback::captureReply, secondCallback::captureError);
            secondCallback.await();

            assertThat(llmClient.lastMessages).containsExactly(
                    new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)),
                    new LlmChatMessage(ChatRole.USER, "hello"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "assistant-1"),
                    new LlmChatMessage(ChatRole.USER, "follow up")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void secondConcurrentRequestReturnsConversationBusy() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        BlockingLlmClient llmClient = new BlockingLlmClient();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor, null, null, null);

            CapturingChatCallback firstCallback = new CapturingChatCallback();
            service.handleChat(buildRequest(), firstCallback::captureReply, firstCallback::captureError);
            llmClient.awaitStarted();

            CapturingChatCallback secondCallback = new CapturingChatCallback();
                service.handleChat(
                    new LlmChatRequest("conversation-1", "user-2", "second", null, false, ChatAgentMode.CHAT, null),
                    secondCallback::captureReply,
                    secondCallback::captureError
            );

            assertThat(secondCallback.reply()).isNull();
            assertThat(secondCallback.errorCode()).isEqualTo(LlmErrorCode.CONVERSATION_BUSY);

            llmClient.release("assistant reply");
            firstCallback.await();
            assertThat(firstCallback.reply().content()).isEqualTo("assistant reply");
        } finally {
            executor.shutdownNow();
        }
    }

    // ── agent path routing tests ─────────────────────────────────────────────

    @Test
    void agentModeDisabledUsesLlmClientNotOrchestrator() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        // agentModeEnabled defaults to false
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "single-pass reply";
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor,
                    null, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of("target-1"), false, ChatAgentMode.CHAT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.reply()).isNotNull();
            assertThat(llmClient.lastMessages).isNotNull();
            verify(orchestrator, never()).run(any(), any(), anyInt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentModeEnabledChatModeUsesLlmClientNotOrchestrator() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "single-pass reply";
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, new ConversationMemory(properties), executor,
                    null, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of(), false, ChatAgentMode.CHAT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.reply()).isNotNull();
            assertThat(llmClient.lastMessages).isNotNull();
            verify(orchestrator, never()).run(any(), any(), anyInt());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentModeEnabledAgentModeUsesOrchestratorNotLlmClient() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        StubLlmClient llmClient = new StubLlmClient();
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        AgentSnapshot snapshot = new AgentSnapshot(1L, null, java.util.Map.of());
        when(snapshotFactory.build()).thenReturn(snapshot);
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("agent reply", 2, 1));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(llmClient, properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "target detail?", List.of("target-1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.reply()).isNotNull();
            assertThat(callback.reply().content()).isEqualTo("agent reply");
            assertThat(callback.reply().provider()).isEqualTo("gemini");
            assertThat(llmClient.lastMessages).isNull();
            verify(orchestrator).run(any(), any(), anyInt(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathSuccessWritesConversationMemory() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("agent answer", 1, 2));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "user question", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(conversationMemory.getHistory("c-1")).containsExactly(
                    new LlmChatMessage(ChatRole.USER, "user question"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "agent answer")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathAllowsToolCallCountZero() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        // toolCallCount == 0 is allowed in chat path
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("conversational answer", 1, 0));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hi", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.reply()).isNotNull();
            assertThat(callback.reply().content()).isEqualTo("conversational answer");
            assertThat(callback.errorCode()).isNull();
            assertThat(conversationMemory.getHistory("c-1")).hasSize(2);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathEmitsFinalizingCompletedStepBeforeReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("agent reply", 1, 1, "finalizing-step-1"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, new ConversationMemory(properties), executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "question", List.of("t1"), false, ChatAgentMode.AGENT, null);

            java.util.concurrent.CopyOnWriteArrayList<AgentStepEvent> stepEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
            java.util.concurrent.CopyOnWriteArrayList<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            service.handleChat(
                    request,
                    reply -> {
                        order.add("reply");
                        latch.countDown();
                    },
                    (code, message) -> {
                        order.add("error");
                        latch.countDown();
                    },
                    step -> {
                        stepEvents.add(step);
                        order.add("step");
                    }
            );

            boolean completed = latch.await(2, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(order).containsExactly("step", "reply");
            assertThat(stepEvents).hasSize(1);
            assertThat(stepEvents.get(0).stepId()).isEqualTo("finalizing-step-1");
            assertThat(stepEvents.get(0).status()).isEqualTo(AgentStepStatus.SUCCEEDED);
            assertThat(stepEvents.get(0).message()).isEqualTo("势态整理完成");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathMaxIterationsExceededReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.maxIterationsExceeded(5, 5));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "question", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.reply()).isNull();
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            assertThat(conversationMemory.getHistory("c-1")).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathProviderFailedReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.providerFailed("LLM_REQUEST_FAILED", "provider error", null));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, new ConversationMemory(properties), executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathToolFailedReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.toolFailed("call-1", "get_target_detail", "tool error", null));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, new ConversationMemory(properties), executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathSnapshotFailureReleasesPermitAndReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        when(snapshotFactory.build()).thenThrow(new IllegalStateException("no snapshot"));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, null, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            // synchronous — no await needed
            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
            // permit should be released: next request should not get CONVERSATION_BUSY
            CapturingChatCallback nextCallback = new CapturingChatCallback();
            properties.setAgentModeEnabled(false);
            StubLlmClient llmClient = new StubLlmClient();
            llmClient.response = "ok";
            LlmChatService service2 = buildService(llmClient, properties, conversationMemory, executor,
                    null, null, null);
            service2.handleChat(new LlmChatRequest("c-1", "e-2", "next", null, false, ChatAgentMode.CHAT, null),
                    nextCallback::captureReply, nextCallback::captureError);
            nextCallback.await();
            assertThat(nextCallback.errorCode()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathPromptBuildFailureReleasesPermitAndReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        ChatAgentPromptBuilder promptBuilder = mock(ChatAgentPromptBuilder.class);
        when(promptBuilder.build(any(), any(), any())).thenThrow(new IllegalStateException("prompt build failed"));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, null, promptBuilder);
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);

            // permit should be released: next request should not get CONVERSATION_BUSY
            CapturingChatCallback nextCallback = new CapturingChatCallback();
            properties.setAgentModeEnabled(false);
            StubLlmClient llmClient = new StubLlmClient();
            llmClient.response = "ok";
            LlmChatService service2 = buildService(llmClient, properties, conversationMemory, executor,
                    null, null, null);
            service2.handleChat(new LlmChatRequest("c-1", "e-2", "next", null, false, ChatAgentMode.CHAT, null),
                    nextCallback::captureReply, nextCallback::captureError);
            nextCallback.await();
            assertThat(nextCallback.errorCode()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathEditLastUserMessageCallsReplaceLastTurn() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any()))
                .thenReturn(AgentLoopResult.completed("edited reply", 1, 1));
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ConversationMemory.ConversationPermit permit = conversationMemory.tryAcquire("c-1");
            permit.close();
            conversationMemory.append("c-1", new LlmChatMessage(ChatRole.USER, "original"));
            conversationMemory.append("c-1", new LlmChatMessage(ChatRole.ASSISTANT, "old reply"));

            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "edited", List.of("t1"), true, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(conversationMemory.getHistory("c-1")).containsExactly(
                    new LlmChatMessage(ChatRole.USER, "edited"),
                    new LlmChatMessage(ChatRole.ASSISTANT, "edited reply")
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void agentPathExecutorRejectionReleasesPermitAndReturnsLlmFailed() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        ExecutorService rejectingExecutor = mock(ExecutorService.class);
        doThrow(new java.util.concurrent.RejectedExecutionException("test rejection"))
                .when(rejectingExecutor).execute(any());
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        LlmChatService service = new LlmChatService(
                new StubLlmClient(), properties, promptTemplateService, riskContextHolder,
                riskContextFormatter, new ExplanationCache(), conversationMemory, rejectingExecutor,
                snapshotFactory, null, new ChatAgentPromptBuilder(promptTemplateService)
        );
        LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_FAILED);
        assertThat(conversationMemory.getHistory("c-1")).isEmpty();

        // permit must be released: follow-up should not get CONVERSATION_BUSY
        properties.setAgentModeEnabled(false);
        StubLlmClient llmClient2 = new StubLlmClient();
        llmClient2.response = "ok";
        ExecutorService executor2 = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service2 = buildService(llmClient2, properties, conversationMemory, executor2,
                    null, null, null);
            CapturingChatCallback nextCallback = new CapturingChatCallback();
            service2.handleChat(new LlmChatRequest("c-1", "e-2", "next", null, false, ChatAgentMode.CHAT, null),
                    nextCallback::captureReply, nextCallback::captureError);
            nextCallback.await();
            assertThat(nextCallback.errorCode()).isNull();
        } finally {
            executor2.shutdownNow();
        }
    }

    @Test
    void agentPathTimeoutReturnsLlmTimeoutAndReleasesPermit() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L);
        properties.setAgentModeEnabled(true);
        properties.setAgentChatTimeoutMs(50L);
        AgentSnapshotFactory snapshotFactory = mock(AgentSnapshotFactory.class);
        AgentLoopOrchestrator orchestrator = mock(AgentLoopOrchestrator.class);
        when(snapshotFactory.build()).thenReturn(new AgentSnapshot(1L, null, java.util.Map.of()));
        when(orchestrator.run(any(), any(), anyInt(), any())).thenAnswer(inv -> {
            Thread.sleep(5000);
            return AgentLoopResult.completed("late reply", 1, 0);
        });
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = buildService(new StubLlmClient(), properties, conversationMemory, executor,
                    snapshotFactory, orchestrator, new ChatAgentPromptBuilder(promptTemplateService));
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "q", List.of("t1"), false, ChatAgentMode.AGENT, null);

            CapturingChatCallback callback = new CapturingChatCallback();
            service.handleChat(request, callback::captureReply, callback::captureError);
            callback.await();

            assertThat(callback.errorCode()).isEqualTo(LlmErrorCode.LLM_TIMEOUT);
            assertThat(callback.reply()).isNull();
            assertThat(conversationMemory.getHistory("c-1")).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LlmProperties buildProperties(boolean enabled, long timeoutMs) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMs(timeoutMs);
        return properties;
    }

    private LlmChatRequest buildRequest() {
        return new LlmChatRequest("conversation-1", "user-1", "hello", null, false, ChatAgentMode.CHAT, null);
    }

    private LlmChatService buildService(
            LlmClient llmClient,
            LlmProperties properties,
            ConversationMemory conversationMemory,
            ExecutorService executor,
            AgentSnapshotFactory snapshotFactory,
            AgentLoopOrchestrator orchestrator,
            ChatAgentPromptBuilder promptBuilder
    ) {
        return new LlmChatService(
                llmClient,
                properties,
                promptTemplateService,
                riskContextHolder,
                riskContextFormatter,
                new ExplanationCache(),
                conversationMemory,
                executor,
                snapshotFactory,
                orchestrator,
                promptBuilder
        );
    }

    private static final class CapturingChatCallback {
        private LlmChatService.ChatReplyResult reply;
        private LlmErrorCode errorCode;
        private String errorMessage;
        private final CountDownLatch latch = new CountDownLatch(1);

        void captureReply(LlmChatService.ChatReplyResult reply) {
            this.reply = reply;
            latch.countDown();
        }

        void captureError(LlmErrorCode errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            latch.countDown();
        }

        void await() throws InterruptedException {
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        }

        LlmChatService.ChatReplyResult reply() {
            return reply;
        }

        LlmErrorCode errorCode() {
            return errorCode;
        }

        String errorMessage() {
            return errorMessage;
        }
    }

    private static final class RecordingLlmClient implements LlmClient {
        private List<LlmChatMessage> lastMessages;

        @Override
        public String chat(List<LlmChatMessage> messages) {
            this.lastMessages = messages;
            return "delegated";
        }

        @Override
        public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException("stub uses chat() override");
        }

        List<LlmChatMessage> lastMessages() {
            return lastMessages;
        }

        public String generateText(String prompt) {
            return chat(List.of(new LlmChatMessage(ChatRole.USER, prompt)));
        }
    }

    private static final class StubLlmClient implements LlmClient {
        private List<LlmChatMessage> lastMessages;
        private String response;
        private RuntimeException failure;

        @Override
        public String chat(List<LlmChatMessage> messages) {
            this.lastMessages = messages;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException("stub uses chat() override");
        }
    }

    private static final class BlockingLlmClient implements LlmClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile String response;

        @Override
        public String chat(List<LlmChatMessage> messages) {
            started.countDown();
            try {
                assertThat(release.await(1, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return response;
        }

        @Override
        public AgentStepResult chatWithTools(List<AgentMessage> messages, List<ToolDefinition> tools) {
            throw new UnsupportedOperationException("stub uses chat() override");
        }

        void awaitStarted() throws InterruptedException {
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        }

        void release(String response) {
            this.response = response;
            release.countDown();
        }
    }
}
