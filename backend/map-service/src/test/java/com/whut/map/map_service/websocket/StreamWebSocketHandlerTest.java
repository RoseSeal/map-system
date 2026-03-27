package com.whut.map.map_service.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.MessageRole;
import com.whut.map.map_service.service.llm.LlmChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StreamWebSocketHandlerTest {

    @Mock
    private WebSocketSessionRegistry sessionRegistry;

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private LlmChatService llmChatService;

    private final BackendMessageFactory backendMessageFactory = new BackendMessageFactory();
    private final ChatMessageFactory chatMessageFactory = new ChatMessageFactory(backendMessageFactory);

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void pingMessagesAreRoutedToUnifiedPongSender() {
        StreamWebSocketHandler handler = new StreamWebSocketHandler(
                sessionRegistry,
                objectMapper,
                webSocketService,
                llmChatService,
                backendMessageFactory,
                chatMessageFactory
        );

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"PING\"}"));

        verify(webSocketService).sendToSession(eq(session), org.mockito.ArgumentMatchers.any(BackendMessage.class));
    }

    @Test
    void chatMessagesAreParsedAndDelegatedToLlmChatService() {
        StreamWebSocketHandler handler = new StreamWebSocketHandler(
                sessionRegistry,
                objectMapper,
                webSocketService,
                llmChatService,
                backendMessageFactory,
                chatMessageFactory
        );

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"CHAT\",\"message\":{\"sequence_id\":\"conversation-1\",\"message_id\":\"user-1\",\"role\":\"user\",\"input_type\":\"TEXT\",\"content\":\"hello\"}}"));

        ArgumentCaptor<FrontendChatPayload> captor = ArgumentCaptor.forClass(FrontendChatPayload.class);
        verify(llmChatService).handleChat(eq(session), captor.capture());
        assertThat(captor.getValue().getSequenceId()).isEqualTo("conversation-1");
        assertThat(captor.getValue().getMessageId()).isEqualTo("user-1");
        assertThat(captor.getValue().getRole()).isEqualTo(MessageRole.USER);
        assertThat(captor.getValue().getContent()).isEqualTo("hello");
    }
}
