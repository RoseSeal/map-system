package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.whut.map.map_service.shared.transport.protocol.ProtocolConnections;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatErrorPayload {

    @JsonProperty("event_id")
    private String eventId;

    @Builder.Default
    @JsonProperty("connection")
    private String connection = ProtocolConnections.CHAT;

    @JsonProperty("error_code")
    private ChatErrorCode errorCode;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("timestamp")
    private String timestamp;
}
