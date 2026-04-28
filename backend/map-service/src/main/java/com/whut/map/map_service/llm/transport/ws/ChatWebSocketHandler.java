package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.llm.agent.AgentStepEvent;
import com.whut.map.map_service.llm.client.LlmClientRegistry;
import com.whut.map.map_service.llm.client.LlmProvider;
import com.whut.map.map_service.llm.client.LlmProviderSelectionStore;
import com.whut.map.map_service.llm.client.LlmTaskType;
import com.whut.map.map_service.llm.config.LlmProperties;
import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.context.ExplanationCache;
import com.whut.map.map_service.llm.memory.ConversationMemory;
import com.whut.map.map_service.llm.service.ChatAgentMode;
import com.whut.map.map_service.llm.service.LlmChatRequest;
import com.whut.map.map_service.llm.service.LlmChatService;
import com.whut.map.map_service.llm.service.LlmErrorCode;
import com.whut.map.map_service.llm.service.LlmVoiceMode;
import com.whut.map.map_service.llm.service.LlmVoiceRequest;
import com.whut.map.map_service.llm.service.SelectedExplanationRef;
import com.whut.map.map_service.llm.service.VoiceChatService;
import com.whut.map.map_service.llm.transport.ws.validation.AudioValidator;
import com.whut.map.map_service.shared.transport.protocol.ProtocolConnections;
import com.whut.map.map_service.shared.transport.protocol.ProtocolFields;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final LlmProperties llmProperties;
    private final WhisperProperties whisperProperties;
    private final LlmChatService llmChatService;
    private final VoiceChatService voiceChatService;
    private final ConversationMemory conversationMemory;
    private final AudioValidator audioValidator;
    private final ExplanationCache explanationCache;
    private final LlmClientRegistry llmClientRegistry;
    private final LlmProviderSelectionStore llmProviderSelectionStore;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(whisperProperties.getWebSocketTextMessageSizeLimitBytes());
        session.setBinaryMessageSizeLimit(whisperProperties.getWebSocketBinaryMessageSizeLimitBytes());
        log.debug("Chat WebSocket connected: {}", session.getId());
        sendCapability(session);
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
            case SET_LLM_PROVIDER_SELECTION -> handleSetLlmProviderSelection(session, envelope.getPayload());
            case CLEAR_EXPIRED_EXPLANATIONS -> handleClearExpiredExplanations(session, envelope.getPayload());
            default -> sendError(session, readText(envelope.getPayload(), ProtocolFields.EVENT_ID), ChatErrorCode.INVALID_CHAT_REQUEST, "Unsupported message type.");
        }
    }

    private void handleSetLlmProviderSelection(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "SET_LLM_PROVIDER_SELECTION payload is required.");
            return;
        }

        SetLlmProviderSelectionPayload payload = tryParsePayload(
                session,
                payloadNode,
                SetLlmProviderSelectionPayload.class,
                ChatErrorCode.INVALID_CHAT_REQUEST,
                "SET_LLM_PROVIDER_SELECTION"
        );
        if (payload == null) {
            return;
        }

        if (!StringUtils.hasText(payload.getEventId())) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "event_id is required for SET_LLM_PROVIDER_SELECTION.");
            return;
        }

        if (payload.getExplanationProvider() == null && payload.getChatProvider() == null) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST,
                    "At least one of explanation_provider or chat_provider must be provided.");
            return;
        }

        if (payload.getExplanationProvider() != null
                && !llmClientRegistry.isProviderAvailableForTask(payload.getExplanationProvider(), LlmTaskType.EXPLANATION)) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST,
                    "explanation_provider is unavailable or unsupported.");
            return;
        }

        if (payload.getChatProvider() != null
                && !llmClientRegistry.isProviderAvailableForTask(payload.getChatProvider(), LlmTaskType.CHAT)) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST,
                    "chat_provider is unavailable or unsupported.");
            return;
        }

        if (payload.getExplanationProvider() != null) {
            llmProviderSelectionStore.updateSelection(LlmTaskType.EXPLANATION, payload.getExplanationProvider());
        }
        if (payload.getChatProvider() != null) {
            llmProviderSelectionStore.updateSelection(LlmTaskType.CHAT, payload.getChatProvider());
        }

        sendLlmProviderSelectionAck(session, payload.getEventId());
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

        ChatAgentMode agentMode = ChatAgentMode.fromNullable(payload.getAgentMode());
        if (agentMode == null) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST, "Invalid agent_mode value.");
            return;
        }

        if (agentMode == ChatAgentMode.AGENT && !llmProperties.isAgentModeEnabled()) {
            sendError(session, payload.getEventId(), ChatErrorCode.INVALID_CHAT_REQUEST, "Agent mode is not enabled on this server.");
            return;
        }

        String conversationId = payload.getConversationId();
        String eventId = payload.getEventId();

        List<SelectedExplanationRef> explanationRefs = buildExplanationRefs(payload.getSelectedExplanationRefs());

        LlmChatRequest request = new LlmChatRequest(
                conversationId,
                eventId,
                payload.getContent(),
                payload.getSelectedTargetIds(),
                payload.getEditLastUserMessage() != null && payload.getEditLastUserMessage(),
                agentMode,
                explanationRefs
        );

        llmChatService.handleChat(
                request,
                result -> sendChatReply(session, conversationId, eventId, result),
                (errorCode, errorMessage) -> sendError(session, eventId, mapToProtocolErrorCode(errorCode), errorMessage),
                step -> sendAgentStep(session, conversationId, eventId, step)
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

    private void handleClearExpiredExplanations(WebSocketSession session, JsonNode payloadNode) {
        if (payloadNode == null || payloadNode.isNull()) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "CLEAR_EXPIRED_EXPLANATIONS payload is required.");
            return;
        }

        ClearExpiredExplanationsPayload payload = tryParsePayload(session, payloadNode, ClearExpiredExplanationsPayload.class, ChatErrorCode.INVALID_CHAT_REQUEST, "CLEAR_EXPIRED_EXPLANATIONS");
        if (payload == null) return;

        if (!StringUtils.hasText(payload.getEventId())) {
            sendError(session, null, ChatErrorCode.INVALID_CHAT_REQUEST, "event_id is required for CLEAR_EXPIRED_EXPLANATIONS.");
            return;
        }

        Instant now = Instant.now();
        List<String> removedEventIds = explanationCache.clearExpiredResolvedExplanations(now);
        sendExpiredExplanationsCleared(session, payload.getEventId(), removedEventIds, now);
    }

    private void sendExpiredExplanationsCleared(
            WebSocketSession session,
            String replyToEventId,
            List<String> removedEventIds,
            Instant cutoffTime
    ) {
        ExpiredExplanationsClearedPayload payload = ExpiredExplanationsClearedPayload.builder()
                .eventId(generateEventId())
                .replyToEventId(replyToEventId)
                .removedEventIds(removedEventIds)
                .cutoffTime(cutoffTime.toString())
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.EXPIRED_EXPLANATIONS_CLEARED, payload);
    }

    private List<SelectedExplanationRef> buildExplanationRefs(List<SelectedExplanationRefPayload> payloadRefs) {
        if (payloadRefs == null || payloadRefs.isEmpty()) {
            return List.of();
        }
        return payloadRefs.stream()
                .filter(ref -> ref != null
                        && StringUtils.hasText(ref.getTargetId())
                        && StringUtils.hasText(ref.getExplanationEventId()))
                .map(ref -> new SelectedExplanationRef(ref.getTargetId(), ref.getExplanationEventId()))
                .toList();
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

    private void sendCapability(WebSocketSession session) {
        boolean llmEnabled = llmProperties.isEnabled();
        boolean chatAvailable = llmEnabled && llmClientRegistry.isTaskAvailable(LlmTaskType.CHAT);
        boolean agentAvailable = llmEnabled
                && llmProperties.isAgentModeEnabled()
                && llmClientRegistry.isTaskAvailable(LlmTaskType.AGENT);
        boolean speechAvailable = llmEnabled;

        Map<String, String> disabledReasons = new LinkedHashMap<>();
        if (!chatAvailable) {
            disabledReasons.put("chat", llmEnabled
                    ? "No available provider for chat task."
                    : "LLM is disabled.");
        }
        if (!agentAvailable) {
            if (!llmEnabled) {
                disabledReasons.put("agent", "LLM is disabled.");
            } else if (!llmProperties.isAgentModeEnabled()) {
                disabledReasons.put("agent", "Agent mode is not enabled on this server.");
            } else {
                disabledReasons.put("agent", "No available provider for agent task.");
            }
        }
        if (!speechAvailable) {
            disabledReasons.put("speech_transcription", "LLM is disabled.");
        }

        ChatCapabilityPayload payload = ChatCapabilityPayload.builder()
                .eventId(generateEventId())
                .chatAvailable(chatAvailable)
                .agentAvailable(agentAvailable)
                .speechTranscriptionAvailable(speechAvailable)
                .disabledReasons(disabledReasons.isEmpty() ? null : disabledReasons)
                .llmProviders(llmClientRegistry.describeCapabilities())
                .effectiveProviderSelection(buildEffectiveProviderSelection())
                .providerSelectionMutable(true)
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.CAPABILITY, payload);
    }

    private void sendLlmProviderSelectionAck(WebSocketSession session, String replyToEventId) {
        LlmProviderSelectionPayload payload = LlmProviderSelectionPayload.builder()
                .eventId(generateEventId())
                .replyToEventId(replyToEventId)
                .effectiveProviderSelection(buildEffectiveProviderSelection())
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.LLM_PROVIDER_SELECTION, payload);
    }

    private EffectiveProviderSelection buildEffectiveProviderSelection() {
        return EffectiveProviderSelection.builder()
                .explanationProvider(resolveEffectiveProvider(LlmTaskType.EXPLANATION))
                .chatProvider(resolveEffectiveProvider(LlmTaskType.CHAT))
                .build();
    }

    private LlmProvider resolveEffectiveProvider(LlmTaskType taskType) {
        try {
            return llmClientRegistry.resolveProviderForTask(taskType);
        } catch (Exception e) {
            return llmProviderSelectionStore.getSelection(taskType);
        }
    }

    private void sendAgentStep(
            WebSocketSession session,
            String conversationId,
            String replyToEventId,
            AgentStepEvent step
    ) {
        AgentStepPayload payload = AgentStepPayload.builder()
                .eventId(generateEventId())
                .conversationId(conversationId)
                .replyToEventId(replyToEventId)
                .stepId(step.stepId())
                .toolName(step.toolName())
                .status(step.status().name())
                .message(step.message())
                .timestamp(Instant.now().toString())
                .build();
        sendDownlink(session, ChatMessageType.AGENT_STEP, payload);
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
