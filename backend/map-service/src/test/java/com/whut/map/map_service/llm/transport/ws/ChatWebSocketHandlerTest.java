package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import com.whut.map.map_service.llm.service.LlmChatService;
import com.whut.map.map_service.llm.transport.ws.validation.AudioValidator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatWebSocketHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blankChatContentReturnsInvalidChatRequest() throws Exception {
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                objectMapper,
                llmProperties(),
                whisperProperties(),
                null,
                null,
                null,
                new AudioValidator(whisperProperties())
        );
        RecordingWebSocketSession session = new RecordingWebSocketSession();

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "CHAT",
                  "source": "client",
                  "payload": {
                    "conversation_id": "conversation-1",
                    "event_id": "event-1",
                    "content": "   "
                  }
                }
                """));

        JsonNode response = lastResponse(session);
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("payload").path("error_code").asText()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST.getValue());
        assertThat(response.path("payload").path("error_message").asText()).isEqualTo("content must not be blank.");
        assertThat(response.path("payload").path("reply_to_event_id").asText()).isEqualTo("event-1");
    }

    @Test
    void invalidSpeechAudioReturnsInvalidAudioFormat() throws Exception {
        WhisperProperties whisperProperties = whisperProperties();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                objectMapper,
                llmProperties(),
                whisperProperties,
                null,
                null,
                null,
                new AudioValidator(whisperProperties)
        );
        RecordingWebSocketSession session = new RecordingWebSocketSession();

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "SPEECH",
                  "source": "client",
                  "payload": {
                    "conversation_id": "conversation-1",
                    "event_id": "event-2",
                    "audio_data": "%%%%",
                    "audio_format": "webm",
                    "mode": "DIRECT"
                  }
                }
                """));

        JsonNode response = lastResponse(session);
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("payload").path("error_code").asText()).isEqualTo(ChatErrorCode.INVALID_AUDIO_FORMAT.getValue());
        assertThat(response.path("payload").path("error_message").asText()).isEqualTo("audio_data is not valid Base64 audio.");
        assertThat(response.path("payload").path("reply_to_event_id").asText()).isEqualTo("event-2");
    }

    @Test
    void malformedBase64PaddingReturnsInvalidAudioFormat() throws Exception {
        WhisperProperties whisperProperties = whisperProperties();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                objectMapper,
                llmProperties(),
                whisperProperties,
                null,
                null,
                null,
                new AudioValidator(whisperProperties)
        );
        RecordingWebSocketSession session = new RecordingWebSocketSession();

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "SPEECH",
                  "source": "client",
                  "payload": {
                    "conversation_id": "conversation-1",
                    "event_id": "event-3",
                    "audio_data": "AA=A",
                    "audio_format": "webm",
                    "mode": "DIRECT"
                  }
                }
                """));

        JsonNode response = lastResponse(session);
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("payload").path("error_code").asText()).isEqualTo(ChatErrorCode.INVALID_AUDIO_FORMAT.getValue());
        assertThat(response.path("payload").path("error_message").asText()).isEqualTo("audio_data is not valid Base64 audio.");
        assertThat(response.path("payload").path("reply_to_event_id").asText()).isEqualTo("event-3");
    }

    @Test
    void missingSpeechModeReturnsInvalidSpeechRequest() throws Exception {
        WhisperProperties whisperProperties = whisperProperties();
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                objectMapper,
                llmProperties(),
                whisperProperties,
                null,
                null,
                null,
                new AudioValidator(whisperProperties)
        );
        RecordingWebSocketSession session = new RecordingWebSocketSession();

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "SPEECH",
                  "source": "client",
                  "payload": {
                    "conversation_id": "conversation-1",
                    "event_id": "event-4",
                    "audio_data": "QUJD",
                    "audio_format": "webm"
                  }
                }
                """));

        JsonNode response = lastResponse(session);
        assertThat(response.path("type").asText()).isEqualTo("ERROR");
        assertThat(response.path("payload").path("error_code").asText()).isEqualTo(ChatErrorCode.INVALID_SPEECH_REQUEST.getValue());
        assertThat(response.path("payload").path("error_message").asText()).isEqualTo("mode is required.");
        assertThat(response.path("payload").path("reply_to_event_id").asText()).isEqualTo("event-4");
    }

    @Test
    void chatPayloadPassesEditLastUserMessageFlagToChatService() throws Exception {
        LlmChatService llmChatService = mock(LlmChatService.class);
        ChatWebSocketHandler handler = new ChatWebSocketHandler(
                objectMapper,
                llmProperties(),
                whisperProperties(),
                llmChatService,
                null,
                null,
                new AudioValidator(whisperProperties())
        );
        RecordingWebSocketSession session = new RecordingWebSocketSession();

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "CHAT",
                  "source": "client",
                  "payload": {
                    "conversation_id": "conversation-1",
                    "event_id": "event-5",
                    "content": "edited content",
                    "edit_last_user_message": true
                  }
                }
                """));

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(llmChatService).handleChat(
                captor.capture(),
                any(),
                any(),
                any()
        );
        LlmChatRequest request = captor.getValue();
        assertThat(request.editLastUserMessage()).isTrue();
        assertThat(request.content()).isEqualTo("edited content");
    }

    private LlmProperties llmProperties() {
        return new LlmProperties();
    }

    private WhisperProperties whisperProperties() {
        WhisperProperties properties = new WhisperProperties();
        properties.setMaxAudioSizeBytes(1024);
        return properties;
    }

    private JsonNode lastResponse(RecordingWebSocketSession session) throws IOException {
        assertThat(session.sentMessages).isNotEmpty();
        TextMessage lastMessage = session.sentMessages.get(session.sentMessages.size() - 1);
        return objectMapper.readTree(lastMessage.getPayload());
    }

    private static final class RecordingWebSocketSession implements WebSocketSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private final List<TextMessage> sentMessages = new ArrayList<>();
        private boolean open = true;
        private int textMessageSizeLimit;
        private int binaryMessageSizeLimit;

        @Override
        public String getId() {
            return "session-1";
        }

        @Override
        public URI getUri() {
            return null;
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return HttpHeaders.EMPTY;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Principal getPrincipal() {
            return null;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return null;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return null;
        }

        @Override
        public String getAcceptedProtocol() {
            return null;
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            this.textMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getTextMessageSizeLimit() {
            return textMessageSizeLimit;
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            this.binaryMessageSizeLimit = messageSizeLimit;
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return binaryMessageSizeLimit;
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return List.of();
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            if (message instanceof TextMessage textMessage) {
                sentMessages.add(textMessage);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(CloseStatus status) {
            open = false;
        }
    }
}
