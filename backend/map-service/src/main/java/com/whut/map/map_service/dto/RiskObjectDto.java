package com.whut.map.map_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import java.util.Map;

@Data
@Builder
public class RiskObjectDto {

    @JsonProperty("risk_object_id")
    private String riskObjectId;

    private long timestamp;

    private Map<String, Object> governance;

    @JsonProperty("own_ship")
    private Map<String, Object> ownShip;

    private Object targets;
}