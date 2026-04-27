package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpiredExplanationsClearedPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("removed_event_ids")
    private List<String> removedEventIds;

    @JsonProperty("cutoff_time")
    private String cutoffTime;

    @JsonProperty("timestamp")
    private String timestamp;
}
