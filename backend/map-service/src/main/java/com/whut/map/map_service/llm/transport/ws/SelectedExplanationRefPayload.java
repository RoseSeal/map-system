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
public class SelectedExplanationRefPayload {

    @JsonProperty("target_id")
    private String targetId;

    @JsonProperty("explanation_event_id")
    private String explanationEventId;
}
