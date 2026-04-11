package com.whut.map.map_service.llm.client;

import com.whut.map.map_service.llm.config.WhisperProperties;
import com.whut.map.map_service.llm.dto.WhisperResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Component
@RequiredArgsConstructor
public class WhisperClientImpl implements WhisperClient {

    private static final String INFERENCE_PATH = "/inference";
    private static final String RESPONSE_FORMAT = "json";

    private final WhisperProperties whisperProperties;

    @Override
    public WhisperResponse transcribe(byte[] audioData, String audioFormat, String language) {
        String normalizedAudioFormat = normalizeAudioFormat(audioFormat);
        String filename = normalizedAudioFormat == null ? "audio.bin" : "audio." + normalizedAudioFormat;

        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(resolveMediaType(normalizedAudioFormat));
        fileHeaders.setContentDisposition(ContentDisposition.formData()
                .name("file")
                .filename(filename)
                .build());

        ByteArrayResource resource = new ByteArrayResource(audioData) {
            @Override
            public String getFilename() {
                return filename;
            }
        };

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new HttpEntity<>(resource, fileHeaders));
        body.add("response_format", RESPONSE_FORMAT);
        if (StringUtils.hasText(language)) {
            body.add("language", language);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<WhisperResponse> response;
        try {
            response = buildRestTemplate().postForEntity(
                    buildInferenceUrl(),
                    new HttpEntity<>(body, headers),
                    WhisperResponse.class
            );
        } catch (RestClientException e) {
            throw e;
        }

        WhisperResponse whisperResponse = response.getBody();
        if (whisperResponse == null) {
            throw new IllegalStateException("Whisper response is null");
        }
        return whisperResponse;
    }

    private String normalizeAudioFormat(String audioFormat) {
        if (!StringUtils.hasText(audioFormat)) {
            return null;
        }
        String normalized = audioFormat.trim().toLowerCase();
        int slashIndex = normalized.indexOf('/');
        if (slashIndex >= 0 && slashIndex < normalized.length() - 1) {
            normalized = normalized.substring(slashIndex + 1);
        }
        int semicolonIndex = normalized.indexOf(';');
        if (semicolonIndex >= 0) {
            normalized = normalized.substring(0, semicolonIndex);
        }
        return switch (normalized) {
            case "x-wav" -> "wav";
            case "mpeg" -> "mp3";
            default -> normalized;
        };
    }

    private MediaType resolveMediaType(String normalizedAudioFormat) {
        if (normalizedAudioFormat == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        return switch (normalizedAudioFormat) {
            case "wav" -> MediaType.parseMediaType("audio/wav");
            case "webm" -> MediaType.parseMediaType("audio/webm");
            case "ogg", "opus" -> MediaType.parseMediaType("audio/ogg");
            case "mp3" -> MediaType.parseMediaType("audio/mpeg");
            case "mp4", "m4a" -> MediaType.parseMediaType("audio/mp4");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMs = Math.toIntExact(whisperProperties.getTimeoutMs());
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);
        return new RestTemplate(requestFactory);
    }

    private String buildInferenceUrl() {
        String baseUrl = whisperProperties.getUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("whisper.url must not be blank");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + INFERENCE_PATH : baseUrl + INFERENCE_PATH;
    }
}
