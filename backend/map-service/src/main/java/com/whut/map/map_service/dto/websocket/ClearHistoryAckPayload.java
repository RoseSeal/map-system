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
public class ClearHistoryAckPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("timestamp")
    private String timestamp;
}
