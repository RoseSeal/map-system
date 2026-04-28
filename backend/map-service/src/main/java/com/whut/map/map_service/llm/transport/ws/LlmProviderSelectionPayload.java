package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmProviderSelectionPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("effective_provider_selection")
    private EffectiveProviderSelection effectiveProviderSelection;

    @JsonProperty("timestamp")
    private String timestamp;
}
