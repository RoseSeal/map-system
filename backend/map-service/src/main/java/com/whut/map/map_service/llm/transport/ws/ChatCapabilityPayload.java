package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.whut.map.map_service.llm.client.LlmProviderCapability;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatCapabilityPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("chat_available")
    private boolean chatAvailable;

    @JsonProperty("agent_available")
    private boolean agentAvailable;

    @JsonProperty("speech_transcription_available")
    private boolean speechTranscriptionAvailable;

    @JsonProperty("disabled_reasons")
    private Map<String, String> disabledReasons;

    @JsonProperty("llm_providers")
    private List<LlmProviderCapability> llmProviders;

    @JsonProperty("effective_provider_selection")
    private EffectiveProviderSelection effectiveProviderSelection;

    @JsonProperty("provider_selection_mutable")
    private boolean providerSelectionMutable;

    @JsonProperty("timestamp")
    private String timestamp;
}
