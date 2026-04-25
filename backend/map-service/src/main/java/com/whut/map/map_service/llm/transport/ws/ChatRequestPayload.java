package com.whut.map.map_service.llm.transport.ws;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequestPayload {

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("content")
    private String content;

    @JsonProperty("selected_target_ids")
    private List<String> selectedTargetIds;

    @JsonProperty("edit_last_user_message")
    private Boolean editLastUserMessage;

    @JsonProperty("agent_mode")
    private String agentMode;
}
