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
public class RecommendedAction {

    @JsonProperty("type")
    private AdvisoryActionType type;

    @JsonProperty("description")
    private String description;

    @JsonProperty("urgency")
    private AdvisoryUrgency urgency;
}
