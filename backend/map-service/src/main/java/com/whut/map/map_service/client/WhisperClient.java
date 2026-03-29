package com.whut.map.map_service.client;

public interface WhisperClient {
    WhisperResponse transcribe(byte[] audioData, String audioFormat, String language);
}
