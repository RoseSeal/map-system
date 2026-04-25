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
public class AgentStepPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("reply_to_event_id")
    private String replyToEventId;

    @JsonProperty("step_id")
    private String stepId;

    @JsonProperty("tool_name")
    private String toolName;

    @JsonProperty("status")
    private String status;

    @JsonProperty("message")
    private String message;

    @JsonProperty("timestamp")
    private String timestamp;
}
