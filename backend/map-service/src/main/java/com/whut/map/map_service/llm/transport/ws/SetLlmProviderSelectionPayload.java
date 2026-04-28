package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.whut.map.map_service.llm.client.LlmProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SetLlmProviderSelectionPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("explanation_provider")
    private LlmProvider explanationProvider;

    @JsonProperty("chat_provider")
    private LlmProvider chatProvider;
}
