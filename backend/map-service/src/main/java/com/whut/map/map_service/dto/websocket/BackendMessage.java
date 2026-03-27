package com.whut.map.map_service.dto.websocket;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class BackendMessage {

    private String type;

    @JsonProperty("sequence_id")
    private String sequenceId;

    private Object payload;

}
