package com.whut.map.map_service.llm.client;

import com.whut.map.map_service.llm.dto.WhisperResponse;

public interface WhisperClient {
    WhisperResponse transcribe(byte[] audioData, String audioFormat, String language);
}
