package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.SpeechMode;
import com.whut.map.map_service.dto.websocket.SpeechRequestPayload;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
class VoiceChatServiceTest {

    private final WhisperProperties whisperProperties = new WhisperProperties();
    private final ChatPayloadValidator chatPayloadValidator = new ChatPayloadValidator(whisperProperties);

    @Test
    void invalidChatEnvelopeReturnsErrorBeforeTranscription() {
        RecordingWhisperClient whisperClient = new RecordingWhisperClient();
        RecordingLlmChatService llmChatService = new RecordingLlmChatService();
        VoiceChatService service = new VoiceChatService(
                whisperClient,
                whisperProperties,
                llmChatService,
                chatPayloadValidator
        );

        SpeechRequestPayload request = SpeechRequestPayload.builder()
                .conversationId("conversation-1")
                .audioData("dGVzdA==")
                .audioFormat("webm")
                .mode(SpeechMode.DIRECT)
                .build();

        CapturingSpeechCallback callback = new CapturingSpeechCallback();
        service.handleVoice(request, callback::captureTranscript, callback::captureReply, callback::captureError);

        assertThat(callback.errorCode()).isEqualTo(ChatErrorCode.INVALID_SPEECH_REQUEST);
        assertThat(callback.errorMessage()).isEqualTo("event_id is required.");
        assertThat(callback.reply()).isNull();
        assertThat(callback.transcript()).isNull();
        assertThat(whisperClient.transcribeCalled).isFalse();
        assertThat(llmChatService.handleChatCalled).isFalse();
    }

    private static final class CapturingSpeechCallback {
        private VoiceChatService.SpeechTranscriptResult transcript;
        private LlmChatService.ChatReplyResult reply;
        private ChatErrorCode errorCode;
        private String errorMessage;

        void captureTranscript(VoiceChatService.SpeechTranscriptResult transcript) {
            this.transcript = transcript;
        }

        void captureReply(LlmChatService.ChatReplyResult reply) {
            this.reply = reply;
        }

        void captureError(ChatErrorCode errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
        }

        VoiceChatService.SpeechTranscriptResult transcript() {
            return transcript;
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

    private static final class RecordingWhisperClient implements WhisperClient {
        private boolean transcribeCalled;

        @Override
        public WhisperResponse transcribe(byte[] audioData, String audioFormat, String language) {
            this.transcribeCalled = true;
            return null;
        }
    }

    private static final class RecordingLlmChatService extends LlmChatService {
        private boolean handleChatCalled;

        RecordingLlmChatService() {
            super(null, new com.whut.map.map_service.config.LlmProperties(), new com.whut.map.map_service.llm.prompt.PromptTemplateService(), new ChatPayloadValidator(new WhisperProperties()));
        }

        @Override
        public void handleChat(
                com.whut.map.map_service.dto.websocket.ChatRequestPayload request,
                java.util.function.Consumer<ChatReplyResult> onSuccess,
                java.util.function.BiConsumer<ChatErrorCode, String> onError
        ) {
            this.handleChatCalled = true;
        }
    }
}
