package com.whut.map.map_service.websocket;

import com.whut.map.map_service.dto.websocket.BackendChatErrorPayload;
import com.whut.map.map_service.dto.websocket.BackendChatReplyPayload;
import com.whut.map.map_service.dto.websocket.BackendChatTranscriptPayload;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.FrontendChatPayload;
import com.whut.map.map_service.dto.websocket.MessageRole;
import com.whut.map.map_service.dto.websocket.WebSocketMessageTypes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChatMessageFactory {

    private final BackendMessageFactory backendMessageFactory;

    public BackendMessage buildReplyMessage(FrontendChatPayload request, String responseText, String source) {
        BackendChatReplyPayload payload = new BackendChatReplyPayload();
        payload.setSequenceId(request.getSequenceId());
        payload.setMessageId(UUID.randomUUID().toString());
        payload.setReplyToMessageId(request.getMessageId());
        payload.setRole(MessageRole.ASSISTANT);
        payload.setContent(responseText);
        payload.setSource(source);
        payload.setTimestamp(Instant.now().toString());
        return backendMessageFactory.buildMessage(WebSocketMessageTypes.CHAT_REPLY, request.getSequenceId(), payload);
    }

    public BackendMessage buildTranscriptMessage(FrontendChatPayload request, String transcript, String language) {
        BackendChatTranscriptPayload payload = new BackendChatTranscriptPayload();
        payload.setSequenceId(request.getSequenceId());
        payload.setMessageId(UUID.randomUUID().toString());
        payload.setReplyToMessageId(request.getMessageId());
        payload.setTranscript(transcript);
        payload.setLanguage(language);
        payload.setTimestamp(Instant.now().toString());
        return backendMessageFactory.buildMessage(WebSocketMessageTypes.CHAT_TRANSCRIPT, request.getSequenceId(), payload);
    }

    public BackendMessage buildErrorMessage(
            String sequenceId,
            String replyToMessageId,
            ChatErrorCode errorCode,
            String errorMessage
    ) {
        BackendChatErrorPayload payload = new BackendChatErrorPayload();
        payload.setSequenceId(sequenceId);
        payload.setMessageId(UUID.randomUUID().toString());
        payload.setReplyToMessageId(replyToMessageId);
        payload.setErrorCode(errorCode);
        payload.setErrorMessage(errorMessage);
        payload.setTimestamp(Instant.now().toString());
        return backendMessageFactory.buildMessage(WebSocketMessageTypes.CHAT_ERROR, sequenceId, payload);
    }
}
