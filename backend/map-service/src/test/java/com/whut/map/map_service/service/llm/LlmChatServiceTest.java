package com.whut.map.map_service.service.llm;

import com.whut.map.map_service.client.LlmClient;
import com.whut.map.map_service.config.LlmProperties;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.BackendChatErrorPayload;
import com.whut.map.map_service.dto.websocket.BackendChatReplyPayload;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.InputType;
import com.whut.map.map_service.dto.websocket.MessageRole;
import com.whut.map.map_service.websocket.BackendMessageFactory;
import com.whut.map.map_service.websocket.ChatMessageFactory;
import com.whut.map.map_service.websocket.WebSocketService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmChatServiceTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private WebSocketService webSocketService;

    @Mock
    private WebSocketSession session;

    private final BackendMessageFactory backendMessageFactory = new BackendMessageFactory();
    private final ChatMessageFactory chatMessageFactory = new ChatMessageFactory(backendMessageFactory);

    @Test
    void validChatRequestsProduceChatReply() {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        LlmChatService service = new LlmChatService(llmClient, properties, webSocketService, chatMessageFactory);
        FrontendChatPayload request = buildRequest();
        when(llmClient.generateText(anyString())).thenReturn("assistant reply");

        service.handleChat(session, request);

        ArgumentCaptor<BackendMessage> captor = ArgumentCaptor.forClass(BackendMessage.class);
        verify(webSocketService, timeout(1000)).sendToSession(eq(session), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("CHAT_REPLY");
        assertThat(captor.getValue().getSequenceId()).isEqualTo("conversation-1");
        BackendChatReplyPayload payload = (BackendChatReplyPayload) captor.getValue().getPayload();
        assertThat(payload.getReplyToMessageId()).isEqualTo("user-1");
        assertThat(payload.getRole()).isEqualTo(MessageRole.ASSISTANT);
        assertThat(payload.getSource()).isEqualTo("zhipu");
        assertThat(payload.getContent()).isEqualTo("assistant reply");
    }

    @Test
    void invalidChatRequestsReturnChatError() {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        LlmChatService service = new LlmChatService(llmClient, properties, webSocketService, chatMessageFactory);
        FrontendChatPayload request = buildRequest();
        request.setContent(" ");

        service.handleChat(session, request);

        ArgumentCaptor<BackendMessage> captor = ArgumentCaptor.forClass(BackendMessage.class);
        verify(webSocketService).sendToSession(eq(session), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("CHAT_ERROR");
        assertThat(captor.getValue().getSequenceId()).isEqualTo("conversation-1");
        BackendChatErrorPayload payload = (BackendChatErrorPayload) captor.getValue().getPayload();
        assertThat(payload.getErrorCode()).isEqualTo(ChatErrorCode.INVALID_CHAT_REQUEST);
        assertThat(payload.getReplyToMessageId()).isEqualTo("user-1");
    }

    @Test
    void llmFailuresReturnChatError() {
        LlmProperties properties = buildProperties(true, 1000L, "zhipu");
        LlmChatService service = new LlmChatService(llmClient, properties, webSocketService, chatMessageFactory);
        FrontendChatPayload request = buildRequest();
        when(llmClient.generateText(anyString())).thenThrow(new IllegalStateException("boom"));

        service.handleChat(session, request);

        ArgumentCaptor<BackendMessage> captor = ArgumentCaptor.forClass(BackendMessage.class);
        verify(webSocketService, timeout(1000)).sendToSession(eq(session), captor.capture());
        assertThat(captor.getValue().getType()).isEqualTo("CHAT_ERROR");
        assertThat(captor.getValue().getSequenceId()).isEqualTo("conversation-1");
        BackendChatErrorPayload payload = (BackendChatErrorPayload) captor.getValue().getPayload();
        assertThat(payload.getErrorCode()).isEqualTo(ChatErrorCode.LLM_REQUEST_FAILED);
        assertThat(payload.getReplyToMessageId()).isEqualTo("user-1");
    }

    private LlmProperties buildProperties(boolean enabled, long timeoutMs, String provider) {
        LlmProperties properties = new LlmProperties();
        properties.setEnabled(enabled);
        properties.setTimeoutMs(timeoutMs);
        properties.setProvider(provider);
        return properties;
    }

    private FrontendChatPayload buildRequest() {
        FrontendChatPayload request = new FrontendChatPayload();
        request.setSequenceId("conversation-1");
        request.setMessageId("user-1");
        request.setRole(MessageRole.USER);
        request.setInputType(InputType.TEXT);
        request.setContent("hello");
        return request;
    }
}
