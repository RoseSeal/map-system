package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmChatServiceTest {

    @Mock
    private LlmClient llmClient;

    private final WhisperProperties whisperProperties = new WhisperProperties();
    private final ChatPayloadValidator chatPayloadValidator = new ChatPayloadValidator(whisperProperties);

    @Test
    void validChatRequestsProduceChatReply() throws Exception {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        LlmChatService service = new LlmChatService(llmClient, properties, chatPayloadValidator);
        ChatRequestPayload request = buildRequest();
        when(llmClient.generateText(anyString())).thenReturn("assistant reply");

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
        assertThat(callback.reply()).isNotNull();
        assertThat(callback.reply().provider()).isEqualTo("zhipu");
        assertThat(callback.reply().content()).isEqualTo("assistant reply");
        assertThat(callback.errorCode()).isNull();
    }

    @Test
    void invalidChatRequestsReturnChatError() {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        LlmChatService service = new LlmChatService(llmClient, properties, chatPayloadValidator);
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
        LlmChatService service = new LlmChatService(llmClient, properties, chatPayloadValidator);
        ChatRequestPayload request = buildRequest();
        when(llmClient.generateText(anyString())).thenThrow(new IllegalStateException("boom"));

        CapturingChatCallback callback = new CapturingChatCallback();
        service.handleChat(request, callback::captureReply, callback::captureError);

        callback.await();
        assertThat(callback.reply()).isNull();
        assertThat(callback.errorCode()).isEqualTo(ChatErrorCode.LLM_REQUEST_FAILED);
        assertThat(callback.errorMessage()).isEqualTo("LLM request failed.");
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

        void captureReply(LlmChatService.ChatReplyResult reply) {
            this.reply = reply;
        }

        void captureError(ChatErrorCode errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        void await() throws InterruptedException {
            Thread.sleep(200);
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
}
