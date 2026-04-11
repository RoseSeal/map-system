package com.whut.map.map_service.service.llm.validation;

import com.whut.map.map_service.config.properties.WhisperProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Base64;

@Component
@RequiredArgsConstructor
public class AudioValidator {

    private final WhisperProperties whisperProperties;

    public Result validateAudio(String audioData, String audioFormat) {
        String normalizedAudioData = AudioPayloadUtils.normalizeAudioData(audioData);
        if (!StringUtils.hasText(normalizedAudioData)) {
            return Result.fail(AudioValidationCode.INVALID_AUDIO_FORMAT, "audio_data is not valid Base64 audio.");
        }

        byte[] decodedAudio;
        try {
            decodedAudio = Base64.getDecoder().decode(normalizedAudioData);
        } catch (IllegalArgumentException e) {
            return Result.fail(AudioValidationCode.INVALID_AUDIO_FORMAT, "audio_data is not valid Base64 audio.");
        }

        if (decodedAudio.length > whisperProperties.getMaxAudioSizeBytes()) {
            return Result.fail(AudioValidationCode.AUDIO_TOO_LARGE, "Audio payload exceeds the maximum size.");
        }

        return Result.ok(decodedAudio);
    }

    public enum AudioValidationCode {
        INVALID_AUDIO_FORMAT,
        AUDIO_TOO_LARGE
    }

    public record Result(
            byte[] decodedAudio,
            AudioValidationCode errorCode,
            String errorMessage
    ) {
        public Result {
            decodedAudio = decodedAudio == null ? null : decodedAudio.clone();
        }

        public static Result ok(byte[] decodedAudio) {
            return new Result(decodedAudio, null, null);
        }

        public static Result fail(AudioValidationCode errorCode, String errorMessage) {
            return new Result(null, errorCode, errorMessage);
        }

        public boolean hasError() {
            return errorCode != null;
        }
    }
}
