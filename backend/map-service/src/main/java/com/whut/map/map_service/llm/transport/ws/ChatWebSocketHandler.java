package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.memory.ConversationMemory;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import com.whut.map.map_service.llm.service.LlmChatService;
import com.whut.map.map_service.llm.service.LlmErrorCode;
import com.whut.map.map_service.llm.service.LlmVoiceMode;
import com.whut.map.map_service.llm.service.LlmVoiceRequest;
import com.whut.map.map_service.llm.service.VoiceChatService;
import com.whut.map.map_service.llm.transport.ws.validation.AudioValidator;
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
    private final ConversationMemory conversationMemory;
    private final AudioValidator audioValidator;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(whisperProperties.getWebSocketTextMessageSizeLimitBytes());
        session.setBinaryMessageSizeLimit(whisperProperties.getWebSocketBinaryMessageSizeLimitBytes());
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
            case CLEAR_HISTORY -> handleClearHistory(session, envelope.getPayload());
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

        ChatRequestPayload payload = tryParsePayload(session, payloadNode, ChatRequestPayload.class, ChatErrorCode.INVALID_CHAT_REQUEST, "CHAT");
        if (payload == null) return;

        String validationMessage = validateChatPayload(payload);
        if (validationMessage != null) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST, validationMessage);
            return;
        }

        LlmChatRequest request = new LlmChatRequest(
                payload.getConversationId(),
                payload.getEventId(),
                payload.getContent(),
                payload.getSelectedTargetIds()
        );

        llmChatService.handleChat(
                request,
                result -> sendChatReply(session, payload.getConversationId(), payload.getEventId(), result),
                (errorCode, errorMessage) -> sendError(session, payload.getEventId(), mapToProtocolErrorCode(errorCode), errorMessage)
        );
    }

    private void handleSpeech(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_SPEECH_REQUEST, "SPEECH payload is required.");
            return;
        }

        SpeechRequestPayload payload = tryParsePayload(session, payloadNode, SpeechRequestPayload.class, ChatErrorCode.INVALID_SPEECH_REQUEST, "SPEECH");
        if (payload == null) return;

        String validationMessage = validateSpeechPayload(payload);
        if (validationMessage != null) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_SPEECH_REQUEST, validationMessage);
            return;
        }

        AudioValidator.Result audioValidation = audioValidator.validateAudio(payload.getAudioData(), payload.getAudioFormat());
        if (audioValidation.hasError()) {
            sendError(session, payload.getEventId(), mapAudioValidationCode(audioValidation.errorCode()), audioValidation.errorMessage());
            return;
        }

        LlmVoiceRequest request = new LlmVoiceRequest(
                payload.getConversationId(),
                payload.getEventId(),
                audioValidation.decodedAudio(),
                payload.getAudioFormat(),
                mapVoiceMode(payload.getMode()),
                payload.getSelectedTargetIds()
        );

        voiceChatService.handleVoice(
                request,
                transcript -> sendSpeechTranscript(session, payload.getConversationId(), payload.getEventId(), transcript),
                reply -> sendChatReply(session, payload.getConversationId(), payload.getEventId(), reply),
                (errorCode, errorMessage) -> sendError(session, payload.getEventId(), mapToProtocolErrorCode(errorCode), errorMessage)
        );
    }

    private void handleClearHistory(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "CLEAR_HISTORY payload is required.");
            return;
        }

        ClearHistoryPayload payload = tryParsePayload(session, payloadNode, ClearHistoryPayload.class, ChatErrorCode.INVALID_CHAT_REQUEST, "CLEAR_HISTORY");
        if (payload == null) return;

        String validationMessage = validateClearHistoryPayload(payload);
        if (validationMessage != null) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST, validationMessage);
            return;
        }

        boolean cleared = conversationMemory.clear(payload.getConversationId());
        if (!cleared) {
            sendError(session, payload.getEventId(), ChatErrorCode.CONVERSATION_BUSY,
                    "Previous request in this conversation is still processing.");
            return;
        }
        sendClearHistoryAck(session, payload.getConversationId(), payload.getEventId());
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

    private void sendClearHistoryAck(
            WebSocketSession session,
            String conversationId,
            String replyToEventId
    ) {
        ClearHistoryAckPayload payload = ClearHistoryAckPayload.builder()
                .eventId(generateEventId())
                .conversationId(conversationId)
                .replyToEventId(replyToEventId)
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.CLEAR_HISTORY_ACK, payload);
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

    private String validateChatPayload(ChatRequestPayload payload) {
        if (payload == null) {
            return "CHAT payload is required.";
        }
        if (!StringUtils.hasText(payload.getConversationId())) {
            return "conversation_id is required.";
        }
        if (!StringUtils.hasText(payload.getEventId())) {
            return "event_id is required.";
        }
        if (!StringUtils.hasText(payload.getContent())) {
            return "content must not be blank.";
        }
        return null;
    }

    private String validateSpeechPayload(SpeechRequestPayload payload) {
        if (payload == null) {
            return "SPEECH payload is required.";
        }
        if (!StringUtils.hasText(payload.getConversationId())) {
            return "conversation_id is required.";
        }
        if (!StringUtils.hasText(payload.getEventId())) {
            return "event_id is required.";
        }
        if (!StringUtils.hasText(payload.getAudioData())) {
            return "audio_data is required.";
        }
        if (!StringUtils.hasText(payload.getAudioFormat())) {
            return "audio_format is required.";
        }
        if (payload.getMode() == null) {
            return "mode is required.";
        }
        return null;
    }

    private String validateClearHistoryPayload(ClearHistoryPayload payload) {
        if (payload == null) {
            return "CLEAR_HISTORY payload is required.";
        }
        if (!StringUtils.hasText(payload.getEventId())) {
            return "event_id is required for CLEAR_HISTORY.";
        }
        if (!StringUtils.hasText(payload.getConversationId())) {
            return "conversation_id is required for CLEAR_HISTORY.";
        }
        return null;
    }

    private <T> T tryParsePayload(WebSocketSession session, JsonNode payloadNode, Class<T> clazz, ChatErrorCode errorCode, String msgTypeLabel) {
        try {
            return objectMapper.treeToValue(payloadNode, clazz);
        } catch (Exception e) {
            sendError(session, readText(payloadNode, ProtocolFields.EVENT_ID), errorCode, msgTypeLabel + " payload format is invalid.");
            return null;
        }
    }

    private ChatErrorCode mapToProtocolErrorCode(LlmErrorCode errorCode) {
        if (errorCode == null) {
            return ChatErrorCode.LLM_REQUEST_FAILED;
        }
        return switch (errorCode) {
            case LLM_TIMEOUT -> ChatErrorCode.LLM_TIMEOUT;
            case LLM_FAILED -> ChatErrorCode.LLM_REQUEST_FAILED;
            case LLM_DISABLED -> ChatErrorCode.LLM_DISABLED;
            case CONVERSATION_BUSY -> ChatErrorCode.CONVERSATION_BUSY;
            case TRANSCRIPTION_FAILED -> ChatErrorCode.TRANSCRIPTION_FAILED;
            case TRANSCRIPTION_TIMEOUT -> ChatErrorCode.TRANSCRIPTION_TIMEOUT;
        };
    }

    private ChatErrorCode mapAudioValidationCode(AudioValidator.AudioValidationCode errorCode) {
        if (errorCode == AudioValidator.AudioValidationCode.AUDIO_TOO_LARGE) {
            return ChatErrorCode.AUDIO_TOO_LARGE;
        }
        return ChatErrorCode.INVALID_AUDIO_FORMAT;
    }

    private LlmVoiceMode mapVoiceMode(SpeechMode mode) {
        return mode == SpeechMode.PREVIEW ? LlmVoiceMode.PREVIEW : LlmVoiceMode.DIRECT;
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
