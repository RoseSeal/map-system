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
public class ChatReplyPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("role")
    private MessageRole role;

    @JsonProperty("content")
    private String content;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("timestamp")
    private String timestamp;
}
