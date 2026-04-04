package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.SpeechMode;
import com.whut.map.map_service.dto.websocket.SpeechRequestPayload;
import com.whut.map.map_service.llm.client.WhisperClient;
import com.whut.map.map_service.service.llm.validation.ChatPayloadValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoiceChatServiceTest {

    @Mock
    private WhisperClient whisperClient;

    @Mock
    private LlmChatService llmChatService;

    private final WhisperProperties whisperProperties = new WhisperProperties();
    private final ChatPayloadValidator chatPayloadValidator = new ChatPayloadValidator(whisperProperties);

    @Test
    void invalidChatEnvelopeReturnsErrorBeforeTranscription() {
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
        verify(whisperClient, never()).transcribe(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(llmChatService, never()).handleChat(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
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
}
