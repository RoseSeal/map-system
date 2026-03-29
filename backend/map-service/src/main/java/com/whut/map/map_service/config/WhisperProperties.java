package com.whut.map.map_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "whisper")
public class WhisperProperties {
    private static final long WEBSOCKET_TEXT_FRAME_OVERHEAD_BYTES = 64 * 1024L;

    private String url = "http://127.0.0.1:8081";
    private long timeoutMs = 60000L;
    private long maxAudioSizeBytes = 10485760L;
    private String defaultLanguage = "zh";

    public int getWebSocketTextMessageSizeLimitBytes() {
        long base64PayloadBytes = ((maxAudioSizeBytes + 2) / 3) * 4;
        return Math.toIntExact(base64PayloadBytes + WEBSOCKET_TEXT_FRAME_OVERHEAD_BYTES);
    }

    public int getWebSocketBinaryMessageSizeLimitBytes() {
        return Math.toIntExact(maxAudioSizeBytes);
    }
}
