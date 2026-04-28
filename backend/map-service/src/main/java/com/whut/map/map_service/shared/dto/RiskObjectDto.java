package com.whut.map.map_service.shared.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class RiskObjectDto {

    @JsonProperty("risk_object_id")
    private String riskObjectId;

    private String timestamp;

    @JsonProperty("environment_state_version")
    private long environmentStateVersion;

    private Map<String, Object> governance;

    @JsonProperty("own_ship")
    private Map<String, Object> ownShip;

    private Object targets;
}
