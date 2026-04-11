package com.whut.map.map_service.llm.transport.ws.validation;

import com.whut.map.map_service.llm.config.WhisperProperties;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AudioValidatorTest {

    @Test
    void emptyAudioReturnsInvalidFormat() {
        AudioValidator validator = new AudioValidator(properties(8));

        AudioValidator.Result result = validator.validateAudio("   ", "webm");

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorCode()).isEqualTo(AudioValidator.AudioValidationCode.INVALID_AUDIO_FORMAT);
    }

    @Test
    void malformedBase64ReturnsInvalidFormat() {
        AudioValidator validator = new AudioValidator(properties(8));

        AudioValidator.Result result = validator.validateAudio("AA=A", "webm");

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorCode()).isEqualTo(AudioValidator.AudioValidationCode.INVALID_AUDIO_FORMAT);
    }

    @Test
    void oversizedDecodedAudioReturnsTooLarge() {
        AudioValidator validator = new AudioValidator(properties(3));
        String audioData = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4});

        AudioValidator.Result result = validator.validateAudio(audioData, "webm");

        assertThat(result.hasError()).isTrue();
        assertThat(result.errorCode()).isEqualTo(AudioValidator.AudioValidationCode.AUDIO_TOO_LARGE);
    }

    private WhisperProperties properties(long maxAudioSizeBytes) {
        WhisperProperties properties = new WhisperProperties();
        properties.setMaxAudioSizeBytes(maxAudioSizeBytes);
        return properties;
    }
}
