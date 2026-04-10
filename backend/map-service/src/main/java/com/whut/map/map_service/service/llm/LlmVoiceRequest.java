package com.whut.map.map_service.service.llm;

import java.util.List;

public record LlmVoiceRequest(
        String conversationId,
        String eventId,
        String audioData,
        String audioFormat,
        LlmVoiceMode mode,
        List<String> selectedTargetIds
) {
    public LlmVoiceRequest {
        selectedTargetIds = selectedTargetIds == null ? List.of() : List.copyOf(selectedTargetIds);
    }
}
