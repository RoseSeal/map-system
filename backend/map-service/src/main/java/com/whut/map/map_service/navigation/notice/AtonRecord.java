package com.whut.map.map_service.navigation.notice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * notice-pipeline 推演出的航标状态；history 按生效时间排序，最后一条决定当前状态。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtonRecord(
        @JsonProperty("aton_id") String atonId,
        @JsonProperty("waterway") String waterway,
        @JsonProperty("name") String name,
        @JsonProperty("is_virtual") boolean virtual,
        @JsonProperty("state") AtonState state,
        @JsonProperty("position") Position position,
        @JsonProperty("last_action") String lastAction,
        @JsonProperty("effective_at") String effectiveAt,
        @JsonProperty("history") List<HistoryEntry> history
) {

    public record Position(
            @JsonProperty("lon") double lon,
            @JsonProperty("lat") double lat
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryEntry(
            @JsonProperty("notice_id") String noticeId,
            @JsonProperty("item_seq") int itemSeq,
            @JsonProperty("action") String action,
            @JsonProperty("effective_at") String effectiveAt,
            @JsonProperty("position_raw") String positionRaw,
            @JsonProperty("position_text") String positionText,
            @JsonProperty("evidence") Map<String, String> evidence
    ) {
    }

    public HistoryEntry latest() {
        return history == null || history.isEmpty() ? null : history.get(history.size() - 1);
    }
}
