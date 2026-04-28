package com.whut.map.map_service.shared.dto.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentUpdatePayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("environment_state_version")
    private long environmentStateVersion;

    @JsonProperty("reason")
    private EnvironmentUpdateReason reason;

    @JsonProperty("changed_fields")
    private List<String> changedFields;

    @JsonProperty("environment_context")
    private Map<String, Object> environmentContext;
}
