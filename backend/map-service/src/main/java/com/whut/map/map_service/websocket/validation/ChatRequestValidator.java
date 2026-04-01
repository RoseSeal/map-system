package com.whut.map.map_service.websocket.validation;

import com.whut.map.map_service.config.WhisperProperties;
import com.whut.map.map_service.dto.websocket.ChatErrorCode;
import com.whut.map.map_service.dto.websocket.ChatRequestPayload;
import com.whut.map.map_service.dto.websocket.SpeechRequestPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class ChatRequestValidator {

    private final WhisperProperties whisperProperties;

    public ValidationResult validateTextRequest(ChatRequestPayload request) {
        if (request == null) {
            return ValidationResult.fail(ChatErrorCode.INVALID_CHAT_REQUEST, "CHAT payload is required.");
        }
        if (!StringUtils.hasText(request.getConversationId())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_CHAT_REQUEST, "conversation_id is required.");
        }
        if (!StringUtils.hasText(request.getEventId())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_CHAT_REQUEST, "event_id is required.");
        }
        if (!StringUtils.hasText(request.getContent())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_CHAT_REQUEST, "content must not be blank.");
        }
        return ValidationResult.ok();
    }

    public ValidationResult validateSpeechRequest(SpeechRequestPayload request) {
        if (request == null) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "SPEECH payload is required.");
        }
        if (!StringUtils.hasText(request.getConversationId())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "conversation_id is required.");
        }
        if (!StringUtils.hasText(request.getEventId())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "event_id is required.");
        }
        String normalizedAudioData = AudioPayloadUtils.normalizeAudioData(request.getAudioData());
        if (!StringUtils.hasText(normalizedAudioData)) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "audio_data is required.");
        }
        if (!StringUtils.hasText(request.getAudioFormat())) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "audio_format is required.");
        }
        if (request.getMode() == null) {
            return ValidationResult.fail(ChatErrorCode.INVALID_SPEECH_REQUEST, "mode is required.");
        }
        if (!isValidBase64(normalizedAudioData)) {
            return ValidationResult.fail(ChatErrorCode.INVALID_AUDIO_FORMAT, "audio_data is not valid Base64 audio.");
        }

        long estimatedSize = estimateDecodedSize(normalizedAudioData);
        if (estimatedSize > whisperProperties.getMaxAudioSizeBytes()) {
            return ValidationResult.fail(ChatErrorCode.AUDIO_TOO_LARGE, "Audio payload exceeds the maximum size.");
        }
        return ValidationResult.ok();
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
