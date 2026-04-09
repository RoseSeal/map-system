package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClearHistoryPayload {

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("event_id")
    private String eventId;
}
