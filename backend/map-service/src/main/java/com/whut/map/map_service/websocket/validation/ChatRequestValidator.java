package com.whut.map.map_service.websocket.validation;

import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.*;
import com.whut.map.map_service.websocket.ChatMessageFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ChatRequestValidator {

    private final ChatMessageFactory chatMessageFactory;
    private final WhisperProperties whisperProperties;

    public ValidationResult validateFrontendMessageType(FrontendMessage frontendMessage) {
        if (frontendMessage == null || !StringUtils.hasText(frontendMessage.getType())) {
            return ValidationResult.fail(buildInvalidChatRequest(null, null, "type is required."));
        }
        if (!WebSocketMessageTypes.PING.equals(frontendMessage.getType())
                && !WebSocketMessageTypes.CHAT.equals(frontendMessage.getType())) {
            return ValidationResult.fail(buildInvalidChatRequest(null, null, "Unsupported message type."));
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateRequestEnvelope(FrontendChatPayload request) {
        if (request == null) {
            return ValidationResult.fail(buildInvalidChatRequest(null, null, "Chat payload is required."));
        }
        if (!StringUtils.hasText(request.getMessageId())) {
            return ValidationResult.fail(buildInvalidChatRequest(request.getSequenceId(), null, "message_id is required."));
        }
        if (request.getRole() != MessageRole.USER) {
            return ValidationResult.fail(buildInvalidChatRequest(
                    request.getSequenceId(),
                    request.getMessageId(),
                    "role must be user."
            ));
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateRouteableChatRequest(FrontendChatPayload request) {
        ValidationResult envelopeResult = validateRequestEnvelope(request);
        if (envelopeResult.hasError()) {
            return envelopeResult;
        }
        if (request.getInputType() == null) {
            return ValidationResult.fail(buildInvalidChatRequest(
                    request.getSequenceId(),
                    request.getMessageId(),
                    "input_type is required."
            ));
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateTextRequest(FrontendChatPayload request) {
        ValidationResult routingResult = validateRouteableChatRequest(request);
        if (routingResult.hasError()) {
            return routingResult;
        }
        if (request.getInputType() != InputType.TEXT) {
            return ValidationResult.fail(buildInvalidChatRequest(
                    request.getSequenceId(),
                    request.getMessageId(),
                    "input_type must be TEXT."
            ));
        }
        if (!StringUtils.hasText(request.getContent())) {
            return ValidationResult.fail(buildInvalidChatRequest(
                    request.getSequenceId(),
                    request.getMessageId(),
                    "content must not be blank."
            ));
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateSpeechRequest(FrontendChatPayload request) {
        ValidationResult routingResult = validateRouteableChatRequest(request);
        if (routingResult.hasError()) {
            return routingResult;
        }
        if (request.getInputType() != InputType.SPEECH) {
            return ValidationResult.fail(buildInvalidChatRequest(
                    request.getSequenceId(),
                    request.getMessageId(),
                    "input_type must be SPEECH."
            ));
        }

        String normalizedAudioData = AudioPayloadUtils.normalizeAudioData(request.getAudioData());
        if (!StringUtils.hasText(normalizedAudioData)) {
            return ValidationResult.fail(chatMessageFactory.buildErrorMessage(
                    request.getSequenceId(),
                    request.getMessageId(),
                    ChatErrorCode.INVALID_AUDIO_FORMAT,
                    "audio_data must not be blank."
            ));
        }
        if (!StringUtils.hasText(request.getAudioFormat())) {
            return ValidationResult.fail(chatMessageFactory.buildErrorMessage(
                    request.getSequenceId(),
                    request.getMessageId(),
                    ChatErrorCode.INVALID_AUDIO_FORMAT,
                    "audio_format is required."
            ));
        }
        if (!isValidBase64(normalizedAudioData)) {
            return ValidationResult.fail(chatMessageFactory.buildErrorMessage(
                    request.getSequenceId(),
                    request.getMessageId(),
                    ChatErrorCode.INVALID_AUDIO_FORMAT,
                    "audio_data is not valid Base64 audio."
            ));
        }

        long estimatedSize = estimateDecodedSize(normalizedAudioData);
        if (estimatedSize > whisperProperties.getMaxAudioSizeBytes()) {
            return ValidationResult.fail(chatMessageFactory.buildErrorMessage(
                    request.getSequenceId(),
                    request.getMessageId(),
                    ChatErrorCode.AUDIO_TOO_LARGE,
                    "Audio payload exceeds the maximum size."
            ));
        }
        return ValidationResult.ok();
    }

    private BackendMessage buildInvalidChatRequest(
            String sequenceId,
            String replyToMessageId,
            String errorMessage
    ) {
        return chatMessageFactory.buildErrorMessage(
                sequenceId,
                replyToMessageId,
                ChatErrorCode.INVALID_CHAT_REQUEST,
                errorMessage
        );
    }

    private boolean isValidBase64(String audioData) {
        if ((audioData.length() % 4) != 0) {
            return false;
        }
        int paddingStart = audioData.indexOf('=');
        if (paddingStart >= 0 && paddingStart < audioData.length() - 2) {
            return false;
        }
        for (int i = 0; i < audioData.length(); i++) {
            char ch = audioData.charAt(i);
            boolean isBase64Char = (ch >= 'A' && ch <= 'Z')
                    || (ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '+'
                    || ch == '/'
                    || ch == '=';
            if (!isBase64Char) {
                return false;
            }
        }
        return true;
    }

    private long estimateDecodedSize(String base64Audio) {
        int padding = 0;
        if (base64Audio.endsWith("==")) {
            padding = 2;
        } else if (base64Audio.endsWith("=")) {
            padding = 1;
        }
        return (base64Audio.length() * 3L) / 4 - padding;
    }
}
