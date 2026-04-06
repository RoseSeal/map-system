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

    @Test
    void validChatRequestsProduceChatReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        StubLlmClient llmClient = new StubLlmClient();
        llmClient.response = "assistant reply";
        LlmChatService service = new LlmChatService(llmClient, properties, promptTemplateService, chatPayloadValidator);
        ChatRequestPayload request = buildRequest();

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
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
        LlmChatService service = new LlmChatService(llmClient, properties, promptTemplateService, chatPayloadValidator);
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
        LlmChatService service = new LlmChatService(llmClient, properties, promptTemplateService, chatPayloadValidator);
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
