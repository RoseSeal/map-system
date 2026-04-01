package com.whut.map.map_service.dto.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskUpdatePayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("risk_object_id")
    private String riskObjectId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("governance")
    private Map<String, Object> governance;

    @JsonProperty("own_ship")
    private Map<String, Object> ownShip;

    @JsonProperty("targets")
    private Object targets;

    @JsonProperty("environment_context")
    private Map<String, Object> environmentContext;
}
