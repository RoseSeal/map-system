package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ChatUplinkEnvelope {

    @JsonProperty("type")
    private String type;

    @JsonProperty("source")
    private String source;

    @JsonProperty("payload")
    private JsonNode payload;
}
