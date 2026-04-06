package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.properties.LlmProperties;
import com.whut.map.map_service.config.properties.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.llm.client.LlmClient;
import com.whut.map.map_service.llm.dto.ChatRole;
import com.whut.map.map_service.llm.dto.LlmChatMessage;
import com.whut.map.map_service.llm.prompt.PromptScene;
import com.whut.map.map_service.llm.prompt.PromptTemplateService;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
class LlmChatServiceTest {

    private final WhisperProperties whisperProperties = new WhisperProperties();
    private final ChatPayloadValidator chatPayloadValidator = new ChatPayloadValidator(whisperProperties);
    private final PromptTemplateService promptTemplateService = new PromptTemplateService();
    private final RiskContextHolder riskContextHolder = new RiskContextHolder();
    private final RiskContextFormatter riskContextFormatter = new RiskContextFormatter(new LlmProperties());

    @Test
    void validChatRequestsProduceChatReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        LlmChatService service = new LlmChatService(
                llmClient,
                properties,
                promptTemplateService,
                riskContextHolder,
                riskContextFormatter,
                chatPayloadValidator
        );
        ChatRequestPayload request = buildRequest();

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
        assertThat(llmClient.lastMessages).hasSize(2);
        assertThat(llmClient.lastMessages).containsExactly(
                new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)),
                new LlmChatMessage(ChatRole.USER, request.getContent())
        );
        assertThat(callback.reply()).isNotNull();
        assertThat(callback.reply().provider()).isEqualTo("zhipu");
        assertThat(callback.reply().content()).isEqualTo("assistant reply");
        assertThat(callback.errorCode()).isNull();
    }

    @Test
    void invalidChatRequestsReturnChatError() {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        LlmChatService service = new LlmChatService(
                llmClient,
                properties,
                promptTemplateService,
                riskContextHolder,
                riskContextFormatter,
                chatPayloadValidator
        );
        ChatRequestPayload request = buildRequest();
        request.setContent(" ");

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        assertThat(callback.reply()).isNull();
        assertThat(callback.errorCode()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST);
        assertThat(callback.errorMessage()).isEqualTo("content must not be blank.");
    }

    @Test
    void llmFailuresReturnChatError() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.failure = new IllegalStateException("boom");
        LlmChatService service = new LlmChatService(
                llmClient,
                properties,
                promptTemplateService,
                riskContextHolder,
                riskContextFormatter,
                chatPayloadValidator
        );
        ChatRequestPayload request = buildRequest();

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
        assertThat(callback.reply()).isNull();
        assertThat(callback.errorCode()).isEqualTo(ChatErrorCode.LLM_REQUEST_FAILED);
        assertThat(callback.errorMessage()).isEqualTo("LLM request failed.");
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
        holder.update(com.whut.map.map_service.llm.dto.LlmRiskContext.builder()
                .ownShip(com.whut.map.map_service.llm.dto.LlmRiskOwnShipContext.builder()
                        .id("own-1")
                        .longitude(120.1234)
                        .latitude(30.5678)
                        .sog(12.3)
                        .cog(87.6)
                        .build())
                .targets(List.of(com.whut.map.map_service.llm.dto.LlmRiskTargetContext.builder()
                        .targetId("target-1")
                        .riskLevel(com.whut.map.map_service.engine.risk.RiskConstants.WARNING)
                        .currentDistanceNm(0.80)
                        .dcpaNm(0.42)
                        .tcpaSec(240)
                        .approaching(true)
                        .build()))
                .build());
        LlmChatService service = new LlmChatService(
                llmClient,
                properties,
                promptTemplateService,
                holder,
                new RiskContextFormatter(properties),
                chatPayloadValidator
        );
        ChatRequestPayload request = buildRequest();

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
        assertThat(llmClient.lastMessages).hasSize(2);
        assertThat(llmClient.lastMessages.get(0))
                .isEqualTo(new LlmChatMessage(ChatRole.SYSTEM, promptTemplateService.getSystemPrompt(PromptScene.CHAT)));
        assertThat(llmClient.lastMessages.get(1).role()).isEqualTo(ChatRole.USER);
        assertThat(llmClient.lastMessages.get(1).content())
                .contains("【当前态势】更新时间:")
                .contains("目标船 target-1: 风险等级 WARNING")
                .contains("【用户问题】")
                .contains(request.getContent());
    }

    private LlmProperties buildProperties(boolean enabled, long timeoutMs, String provider) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMs(timeoutMs);
        properties.setProvider(provider);
        return properties;
    }

    private ChatRequestPayload buildRequest() {
        return ChatRequestPayload.builder()
                .conversationId("conversation-1")
                .eventId("user-1")
                .content("hello")
                .build();
    }

    private static final class CapturingChatCallback {
        private LlmChatService.ChatReplyResult reply;
        private ChatErrorCode errorCode;
        private String errorMessage;
        private final CountDownLatch latch = new CountDownLatch(1);

        void captureReply(LlmChatService.ChatReplyResult reply) {
            this.reply = reply;
            latch.countDown();
        }

        void captureError(ChatErrorCode errorCode, String errorMessage) {
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

        ChatErrorCode errorCode() {
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
}
