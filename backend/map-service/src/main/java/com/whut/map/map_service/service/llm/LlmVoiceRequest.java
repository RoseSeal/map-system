package com.whut.map.map_service.service.llm;

import java.util.List;

public record LlmVoiceRequest(
        String conversationId,
        String eventId,
        byte[] audioBytes,
        String audioFormat,
        LlmVoiceMode mode,
        List<String> selectedTargetIds
) {
    public LlmVoiceRequest {
        audioBytes = audioBytes == null ? null : audioBytes.clone();
        selectedTargetIds = selectedTargetIds == null ? List.of() : List.copyOf(selectedTargetIds);
    }
}
