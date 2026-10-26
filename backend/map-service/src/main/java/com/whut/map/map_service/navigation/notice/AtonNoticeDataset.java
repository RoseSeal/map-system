package com.whut.map.map_service.navigation.notice;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * notice-pipeline 产物 aton_state.json 的结构（step-1 §3.2）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AtonNoticeDataset(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("publisher") String publisher,
        @JsonProperty("coverage") Coverage coverage,
        @JsonProperty("assumptions") List<String> assumptions,
        @JsonProperty("atons") List<AtonRecord> atons,
        @JsonProperty("notices") List<NoticeMeta> notices,
        @JsonProperty("review") List<ReviewEntry> review
) {

    public record Coverage(
            @JsonProperty("published_from") String publishedFrom,
            @JsonProperty("published_to") String publishedTo,
            @JsonProperty("fetched_at") String fetchedAt
    ) {
    }

    public record NoticeMeta(
            @JsonProperty("notice_id") String noticeId,
            @JsonProperty("notice_no") String noticeNo,
            @JsonProperty("title") String title,
            @JsonProperty("published_at") String publishedAt,
            @JsonProperty("url") String url
    ) {
    }

    public record ReviewEntry(
            @JsonProperty("notice_id") String noticeId,
            @JsonProperty("item_seq") Integer itemSeq,
            @JsonProperty("reason") String reason,
            @JsonProperty("detail") String detail
    ) {
    }
}
