package com.whut.map.map_service.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.dto.websocket.BackendChatReplyPayload;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.MessageRole;
import com.whut.map.map_service.dto.websocket.WebSocketMessageTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketServiceTest {

    @Mock
    private WebSocketSessionRegistry sessionRegistry;

    @Mock
    private WebSocketSession session;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void broadcastSerializesBackendMessageAsProvided() throws Exception {
        WebSocketService webSocketService = new WebSocketService(sessionRegistry, objectMapper);
        BackendMessage message = new BackendMessage();
        message.setType(WebSocketMessageTypes.RISK_UPDATE);
        message.setSequenceId("101");
        message.setPayload(objectMapper.readTree("{\"risk_object_id\":\"risk-1\"}"));

        webSocketService.broadcast(message);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).broadcast(captor.capture());
        JsonNode envelope = objectMapper.readTree(captor.getValue());
        assertThat(envelope.get("type").asText()).isEqualTo(WebSocketMessageTypes.RISK_UPDATE);
        assertThat(envelope.get("sequence_id").asText()).isEqualTo("101");
        assertThat(envelope.get("payload").get("risk_object_id").asText()).isEqualTo("risk-1");
    }

    @Test
    void sendToSessionSerializesBackendMessageAsProvided() throws Exception {
        WebSocketService webSocketService = new WebSocketService(sessionRegistry, objectMapper);
        BackendChatReplyPayload payload = new BackendChatReplyPayload();
        payload.setSequenceId("conversation-1");
        payload.setMessageId("assistant-1");
        payload.setReplyToMessageId("user-1");
        payload.setRole(MessageRole.ASSISTANT);
        payload.setContent("reply");
        payload.setTimestamp("2026-03-26T20:00:00Z");
        BackendMessage message = new BackendMessage();
        message.setType(WebSocketMessageTypes.CHAT_REPLY);
        message.setSequenceId("conversation-1");
        message.setPayload(payload);

        webSocketService.sendToSession(session, message);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(sessionRegistry).sendToSession(eq(session), captor.capture());
        JsonNode envelope = objectMapper.readTree(captor.getValue());
        assertThat(envelope.get("type").asText()).isEqualTo(WebSocketMessageTypes.CHAT_REPLY);
        assertThat(envelope.get("sequence_id").asText()).isEqualTo("conversation-1");
        assertThat(envelope.get("payload").get("reply_to_message_id").asText()).isEqualTo("user-1");
        assertThat(envelope.get("payload").get("role").asText()).isEqualTo("assistant");
    }
}
