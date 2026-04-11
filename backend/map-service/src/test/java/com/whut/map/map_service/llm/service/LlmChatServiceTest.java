package com.whut.map.map_service.llm.service;

import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.domain.RiskLevel;
import com.whut.map.map_service.llm.client.LlmClient;
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

class LlmChatServiceTest {

    private final PromptTemplateService promptTemplateService = new PromptTemplateService();
    private final RiskContextHolder riskContextHolder = new RiskContextHolder();
    private final RiskContextFormatter riskContextFormatter = new RiskContextFormatter(new LlmProperties());

    @Test
    void validChatRequestsProduceChatReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    conversationMemory,
                    executor
            );
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
            assertThat(callback.reply().provider()).isEqualTo("zhipu");
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        properties.setEnabled(false);
        StubLlmClient llmClient = new StubLlmClient();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    new ConversationMemory(properties),
                    executor
            );
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    new ConversationMemory(properties),
                    executor
            );
            LlmChatRequest request = new LlmChatRequest("conversation-1", "event-1", "   ", List.of());

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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.failure = new IllegalStateException("boom");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    new ConversationMemory(properties),
                    executor
            );
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
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
                    new ConversationMemory(properties),
                    executor
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
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
                    new ConversationMemory(properties),
                    executor
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
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
                    new RiskContextFormatter(properties), new ConversationMemory(properties), executor);
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "它距离多少", List.of("target-1"));

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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
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
                    new RiskContextFormatter(properties), new ConversationMemory(properties), executor);
            LlmChatRequest request = new LlmChatRequest("c-1", "e-1", "hello", List.of("nonexistent"));

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
    void laterRequestsIncludeConversationHistory() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        ConversationMemory conversationMemory = new ConversationMemory(properties);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    conversationMemory,
                    executor
            );

            LlmChatRequest first = buildRequest();
            llmClient.response = "assistant-1";
            CapturingChatCallback firstCallback = new CapturingChatCallback();
            service.handleChat(first, firstCallback::captureReply, firstCallback::captureError);
            firstCallback.await();

            LlmChatRequest second = new LlmChatRequest(
                    first.conversationId(),
                    "user-2",
                    "follow up",
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
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        BlockingLlmClient llmClient = new BlockingLlmClient();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            LlmChatService service = new LlmChatService(
                    llmClient,
                    properties,
                    promptTemplateService,
                    riskContextHolder,
                    riskContextFormatter,
                    new ConversationMemory(properties),
                    executor
            );

            CapturingChatCallback firstCallback = new CapturingChatCallback();
            service.handleChat(buildRequest(), firstCallback::captureReply, firstCallback::captureError);
            llmClient.awaitStarted();

            CapturingChatCallback secondCallback = new CapturingChatCallback();
            service.handleChat(
                    new LlmChatRequest("conversation-1", "user-2", "second", null),
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

    private LlmProperties buildProperties(boolean enabled, long timeoutMs, String provider) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMs(timeoutMs);
        properties.setProvider(provider);
        return properties;
    }

    private LlmChatRequest buildRequest() {
        return new LlmChatRequest("conversation-1", "user-1", "hello", null);
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
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
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

        List<LlmChatMessage> lastMessages() {
            return lastMessages;
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

        void awaitStarted() throws InterruptedException {
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        }

        void release(String response) {
            this.response = response;
            release.countDown();
        }
    }
}
