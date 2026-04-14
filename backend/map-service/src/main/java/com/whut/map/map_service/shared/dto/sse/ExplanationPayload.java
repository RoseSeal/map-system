package com.whut.map.map_service.shared.dto.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplanationPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("risk_object_id")
    private String riskObjectId;

    @JsonProperty("target_id")
    private String targetId;

    @JsonProperty("risk_level")
    private String riskLevel;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("text")
    private String text;

    @JsonProperty("timestamp")
    private String timestamp;
}
