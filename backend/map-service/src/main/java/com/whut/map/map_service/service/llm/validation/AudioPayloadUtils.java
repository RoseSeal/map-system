package com.whut.map.map_service.service.llm.validation;

import org.springframework.util.StringUtils;

public final class AudioPayloadUtils {

    private AudioPayloadUtils() {
    }

    public static String normalizeAudioData(String audioData) {
        if (!StringUtils.hasText(audioData)) {
            return null;
        }
        String normalized = audioData.trim();
        int separatorIndex = normalized.indexOf(',');
        if (separatorIndex >= 0 && normalized.substring(0, separatorIndex).contains(";base64")) {
            normalized = normalized.substring(separatorIndex + 1);
        }
        return normalized.replaceAll("\\s+", "");
    }
}
