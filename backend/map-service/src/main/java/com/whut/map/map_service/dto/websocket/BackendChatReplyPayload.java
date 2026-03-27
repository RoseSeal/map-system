package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BackendChatReplyPayload {

    @JsonProperty("sequence_id")
    private String sequenceId;

    @JsonProperty("message_id")
    private String messageId;

    @JsonProperty("reply_to_message_id")
    private String replyToMessageId;

    private MessageRole role;

    private String content;

    private String source;

    @JsonProperty("target_id")
    private String targetId;

    private String timestamp;

}
