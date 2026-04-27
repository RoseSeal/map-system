package com.whut.map.map_service.llm.service;

import java.util.List;

public record LlmChatRequest(
        String conversationId,
        String eventId,
        String content,
        List<String> selectedTargetIds,
        boolean editLastUserMessage,
        ChatAgentMode agentMode,
        List<SelectedExplanationRef> selectedExplanationRefs
) {
    public LlmChatRequest {
        selectedTargetIds = selectedTargetIds == null ? List.of() : List.copyOf(selectedTargetIds);
        agentMode = agentMode == null ? ChatAgentMode.CHAT : agentMode;
        selectedExplanationRefs = selectedExplanationRefs == null ? List.of() : List.copyOf(selectedExplanationRefs);
    }
}
