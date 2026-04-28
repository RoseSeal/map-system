package com.whut.map.map_service.llm.client;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class LlmProviderCapability {

    public enum LlmQuotaStatus {
        UNKNOWN,
        AVAILABLE,
        LIMITED,
        EXHAUSTED
    }

    @JsonProperty("provider")
    private LlmProvider provider;

    @JsonProperty("display_name")
    private String displayName;

    @JsonProperty("available")
    private boolean available;

    @JsonProperty("supported_tasks")
    private List<LlmTaskType> supportedTasks;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    @JsonProperty("degraded_tasks")
    private List<LlmTaskType> degradedTasks;

    @JsonProperty("quota_status")
    private LlmQuotaStatus quotaStatus;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("disabled_reason")
    private String disabledReason;
}
