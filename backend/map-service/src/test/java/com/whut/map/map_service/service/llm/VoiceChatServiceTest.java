package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.WhisperClient;
import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.BackendChatErrorPayload;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.InputType;
import com.whut.map.map_service.dto.websocket.MessageRole;
import com.whut.map.map_service.websocket.BackendMessageFactory;
import com.whut.map.map_service.websocket.ChatMessageFactory;
import com.whut.map.map_service.websocket.WebSocketService;
import com.whut.map.map_service.websocket.validation.ChatRequestValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VoiceChatServiceTest {

    @Mock
    private WhisperClient whisperClient;

    @Mock
    private LlmChatService llmChatService;

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private WebSocketSession session;

    private final BackendMessageFactory backendMessageFactory = new BackendMessageFactory();
    private final ChatMessageFactory chatMessageFactory = new ChatMessageFactory(backendMessageFactory);
    private final WhisperProperties whisperProperties = new WhisperProperties();
    private final ChatRequestValidator chatRequestValidator = new ChatRequestValidator(chatMessageFactory, whisperProperties);

    @Test
    void invalidChatEnvelopeReturnsErrorBeforeTranscription() {
        VoiceChatService service = new VoiceChatService(
                whisperClient,
                whisperProperties,
                llmChatService,
                webSocketService,
                chatMessageFactory,
                chatRequestValidator
        );

        FrontendChatPayload request = new FrontendChatPayload();
        request.setSequenceId("conversation-1");
        request.setRole(MessageRole.USER);
        request.setInputType(InputType.SPEECH);
        request.setAudioData("dGVzdA==");
        request.setAudioFormat("webm");

        service.handleVoice(session, request);

        ArgumentCaptor<BackendMessage> captor = ArgumentCaptor.forClass(BackendMessage.class);
        verify(webSocketService).sendToSession(eq(session), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("CHAT_ERROR");
        BackendChatErrorPayload payload = (BackendChatErrorPayload) captor.getValue().getPayload();
        assertThat(payload.getErrorCode()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST);
        assertThat(payload.getReplyToMessageId()).isNull();
        verify(whisperClient, never()).transcribe(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        verify(llmChatService, never()).handleChat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
