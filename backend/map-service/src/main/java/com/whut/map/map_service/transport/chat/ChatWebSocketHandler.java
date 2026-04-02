package com.whut.map.map_service.transport.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.*;
import com.whut.map.map_service.service.llm.LlmChatService;
import com.whut.map.map_service.service.llm.VoiceChatService;
import com.whut.map.map_service.transport.protocol.ProtocolConnections;
import com.whut.map.map_service.transport.protocol.ProtocolFields;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final WhisperProperties whisperProperties;
    private final LlmChatService llmChatService;
    private final VoiceChatService voiceChatService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(whisperProperties.getWebSocketTextMessageSizeLimitBytes());
        session.setBinaryMessageSizeLimit(whisperProperties.getWebSocketBinaryMessageSizeLimitBytes());
        session.getAttributes().put(ProtocolFields.CHAT_SEQUENCE_ATTRIBUTE, new AtomicLong(0L));
        log.debug("Chat WebSocket connected: {}", session.getId());
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            ChatUplinkEnvelope envelope = objectMapper.readValue(message.getPayload(), ChatUplinkEnvelope.class);
            routeMessage(session, envelope);
        } catch (Exception e) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "Chat envelope format is invalid.");
            log.warn("Failed to parse chat envelope from session {}: {}", session.getId(), e.getMessage());
        }
    }

    private void routeMessage(WebSocketSession session, ChatUplinkEnvelope envelope) {
        if (envelope == null || !StringUtils.hasText(envelope.getType())) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "type is required.");
            return;
        }

        ChatMessageSource source = ChatMessageSource.fromValue(envelope.getSource());
        if (source != ChatMessageSource.CLIENT) {
            sendError(session, readText(envelope.getPayload(), ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_CHAT_REQUEST, "source must be client.");
            return;
        }

        ChatMessageType type = ChatMessageType.fromValue(envelope.getType());
        if (type == null) {
            sendError(session, readText(envelope.getPayload(), ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_CHAT_REQUEST, "Unsupported message type.");
            return;
        }

        switch (type) {
            case PING -> handlePing(session, envelope.getPayload());
            case CHAT -> handleChat(session, envelope.getPayload());
            case SPEECH -> handleSpeech(session, envelope.getPayload());
            default -> sendError(session, readText(envelope.getPayload(), ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_CHAT_REQUEST, "Unsupported message type.");
        }
    }

    private void handlePing(WebSocketSession session, JsonNode payload) {
        if (payload != null && !payload.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "PING payload must be null.");
            return;
        }
        sendDownlink(session, ChatMessageType.PONG, null);
    }

    private void handleChat(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "CHAT payload is required.");
            return;
        }

        final ChatRequestPayload payload;
        try {
            payload = objectMapper.treeToValue(payloadNode, ChatRequestPayload.class);
        } catch (Exception e) {
            sendError(session, readText(payloadNode, ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_CHAT_REQUEST, "CHAT payload format is invalid.");
            return;
        }

        llmChatService.handleChat(
                payload,
                result -> sendChatReply(session, payload.getConversationId(), payload.getEventId(), result),
                (errorCode, errorMessage) -> sendError(session, payload.getEventId(), errorCode, errorMessage)
        );
    }

    private void handleSpeech(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_SPEECH_REQUEST, "SPEECH payload is required.");
            return;
        }

        final SpeechRequestPayload payload;
        try {
            payload = objectMapper.treeToValue(payloadNode, SpeechRequestPayload.class);
        } catch (Exception e) {
            sendError(session, readText(payloadNode, ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_SPEECH_REQUEST, "SPEECH payload format is invalid.");
            return;
        }

        voiceChatService.handleVoice(
                payload,
                transcript -> sendSpeechTranscript(session, payload.getConversationId(), payload.getEventId(), transcript),
                reply -> sendChatReply(session, payload.getConversationId(), payload.getEventId(), reply),
                (errorCode, errorMessage) -> sendError(session, payload.getEventId(), errorCode, errorMessage)
        );
    }

    private void sendChatReply(
            WebSocketSession session,
            String conversationId,
            String replyToEventId,
            LlmChatService.ChatReplyResult reply
    ) {
        ChatReplyPayload payload = ChatReplyPayload.builder()
                .eventId(generateEventId())
                .conversationId(conversationId)
                .replyToEventId(replyToEventId)
                .role(MessageRole.ASSISTANT)
                .content(reply.content())
                .provider(reply.provider())
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.CHAT_REPLY, payload);
    }

    private void sendSpeechTranscript(
            WebSocketSession session,
            String conversationId,
            String replyToEventId,
            VoiceChatService.SpeechTranscriptResult transcript
    ) {
        SpeechTranscriptPayload payload = SpeechTranscriptPayload.builder()
                .eventId(generateEventId())
                .conversationId(conversationId)
                .replyToEventId(replyToEventId)
                .transcript(transcript.transcript())
                .language(transcript.language())
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.SPEECH_TRANSCRIPT, payload);
    }

    private void sendError(
            WebSocketSession session,
            String replyToEventId,
            ChatErrorCode errorCode,
            String errorMessage
    ) {
        ChatErrorPayload payload = ChatErrorPayload.builder()
                .eventId(generateEventId())
                .connection(ProtocolConnections.CHAT)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .replyToEventId(replyToEventId)
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.ERROR, payload);
    }

    private void sendDownlink(WebSocketSession session, ChatMessageType type, Object payload) {
        if (session == null || !session.isOpen()) {
            return;
        }

        ChatDownlinkEnvelope envelope = ChatDownlinkEnvelope.builder()
                .type(type.getValue())
                .source(ChatMessageSource.SERVER.getValue())
                .sequenceId(nextSequenceId(session))
                .payload(payload)
                .build();

        try {
            String json = objectMapper.writeValueAsString(envelope);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.error("Failed to send chat message type={} to session {}: {}", type.getValue(), session.getId(), e.getMessage());
        }
    }

    private String nextSequenceId(WebSocketSession session) {
        Object value = session.getAttributes().computeIfAbsent(
                ProtocolFields.CHAT_SEQUENCE_ATTRIBUTE,
                key -> new AtomicLong(0L)
        );
        return String.valueOf(((AtomicLong) value).incrementAndGet());
    }

    private String generateEventId() {
        return UUID.randomUUID().toString();
    }

    private String readText(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return null;
        }
        return field.asText();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("Chat transport error in session {}: {}", session.getId(), exception.getMessage());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.debug("Chat WebSocket closed: {}, status={}", session.getId(), status);
    }
}
