package com.whut.map.map_service.shared.dto.sse;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.whut.map.map_service.shared.domain.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdvisoryPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("advisory_id")
    private String advisoryId;

    @JsonProperty("risk_object_id")
    private String riskObjectId;

    @JsonProperty("snapshot_version")
    private long snapshotVersion;

    @JsonProperty("scope")
    private AdvisoryScope scope;

    @JsonProperty("status")
    private AdvisoryStatus status;

    @JsonProperty("supersedes_id")
    private String supersedesId;

    @JsonProperty("valid_until")
    private String validUntil;

    @JsonProperty("risk_level")
    private RiskLevel riskLevel;

    @JsonProperty("provider")
    private String provider;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("affected_targets")
    private List<String> affectedTargets;

    @JsonProperty("recommended_action")
    private RecommendedAction recommendedAction;

    @JsonProperty("evidence_items")
    private List<String> evidenceItems;
}
