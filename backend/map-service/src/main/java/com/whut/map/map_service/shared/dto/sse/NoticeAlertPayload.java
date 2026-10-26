package com.whut.map.map_service.shared.dto.sse;

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
public class NoticeAlertPayload {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("alert_id")
    private String alertId;

    @JsonProperty("status")
    private NoticeAlertStatus status;

    @JsonProperty("clear_reason")
    private NoticeAlertClearReason clearReason;

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("route_id")
    private String routeId;

    @JsonProperty("aton")
    private Aton aton;

    @JsonProperty("distance_along_route_m")
    private double distanceAlongRouteM;

    @JsonProperty("lateral_offset_m")
    private double lateralOffsetM;

    @JsonProperty("eta_seconds")
    private Long etaSeconds;

    @JsonProperty("buffer_m")
    private double bufferM;

    @JsonProperty("lookahead_minutes")
    private double lookaheadMinutes;

    @JsonProperty("notice")
    private Notice notice;

    @JsonProperty("evidence")
    private Map<String, String> evidence;

    @JsonProperty("data_cutoff")
    private String dataCutoff;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Aton {

        @JsonProperty("aton_id")
        private String atonId;

        @JsonProperty("name")
        private String name;

        @JsonProperty("waterway")
        private String waterway;

        @JsonProperty("is_virtual")
        private boolean virtual;

        @JsonProperty("state")
        private String state;

        @JsonProperty("last_action")
        private String lastAction;

        @JsonProperty("effective_at")
        private String effectiveAt;

        @JsonProperty("lon")
        private double lon;

        @JsonProperty("lat")
        private double lat;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Notice {

        @JsonProperty("notice_id")
        private String noticeId;

        @JsonProperty("notice_no")
        private String noticeNo;

        @JsonProperty("title")
        private String title;

        @JsonProperty("published_at")
        private String publishedAt;

        @JsonProperty("url")
        private String url;

        @JsonProperty("item_seq")
        private int itemSeq;
    }
}
