package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BackendChatErrorPayload {

    @JsonProperty("sequence_id")
    private String sequenceId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("reply_to_message_id")
    private String replyToMessageId;

    @JsonProperty("error_code")
    private ChatErrorCode errorCode;

    @JsonProperty("error_message")
    private String errorMessage;

    private String timestamp;
}
