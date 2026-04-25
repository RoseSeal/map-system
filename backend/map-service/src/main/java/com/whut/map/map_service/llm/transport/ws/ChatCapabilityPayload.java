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
public class ChatCapabilityPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("chat_available")
    private boolean chatAvailable;

    @JsonProperty("agent_available")
    private boolean agentAvailable;

    @JsonProperty("speech_transcription_available")
    private boolean speechTranscriptionAvailable;

    @JsonProperty("timestamp")
    private String timestamp;
}
