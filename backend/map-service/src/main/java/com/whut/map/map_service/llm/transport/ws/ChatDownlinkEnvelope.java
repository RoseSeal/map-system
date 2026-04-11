package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatDownlinkEnvelope {

    @JsonProperty("type")
    private String type;

    @JsonProperty("source")
    private String source;

    @JsonProperty("sequence_id")
    private String sequenceId;

    @JsonProperty("payload")
    private Object payload;
}
