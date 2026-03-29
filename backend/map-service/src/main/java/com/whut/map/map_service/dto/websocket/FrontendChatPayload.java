package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class FrontendChatPayload {

    @JsonProperty("sequence_id")
    private String sequenceId;

    @JsonProperty("message_id")
    private String messageId;

    private MessageRole role;

    @JsonProperty("input_type")
    private InputType inputType;

    private String content;

    @JsonProperty("audio_data")
    private String audioData;

    @JsonProperty("audio_format")
    private String audioFormat;

    private String mode;
}
