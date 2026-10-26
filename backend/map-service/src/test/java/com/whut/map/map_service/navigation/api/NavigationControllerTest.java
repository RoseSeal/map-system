package com.whut.map.map_service.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.navigation.NavigationTestSupport;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertPayload;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NavigationControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private NavigationController controller() {
        var properties = NavigationTestSupport.productionProperties();
        return new NavigationController(
                NavigationTestSupport.routeRepository(properties),
                NavigationTestSupport.atonRepository(properties));
    }

    @Test
    void atonNoticesSerializeAsGeoJsonWithCoverage() {
        JsonNode collection = mapper.valueToTree(controller().atonNotices());

        assertThat(collection.path("type").asText()).isEqualTo("FeatureCollection");
        assertThat(collection.path("coverage").path("published_to").asText()).isEqualTo("2026-10-23");
        assertThat(collection.path("assumptions")).isNotEmpty();

        JsonNode bridgeBuoy = null;
        for (JsonNode feature : collection.path("features")) {
            if ("武汉二桥#1红浮".equals(feature.path("properties").path("name").asText())) {
                bridgeBuoy = feature;
            }
        }
        assertThat(bridgeBuoy).isNotNull();
        assertThat(bridgeBuoy.path("geometry").path("type").asText()).isEqualTo("Point");
        assertThat(bridgeBuoy.path("geometry").path("coordinates").get(0).asDouble()).isEqualTo(114.3228333);
        JsonNode properties = bridgeBuoy.path("properties");
        assertThat(properties.path("is_virtual").asBoolean()).isTrue();
        assertThat(properties.path("state").asText()).isEqualTo("VALID");
        JsonNode history = properties.path("history").get(0);
        assertThat(history.path("position_raw").asText()).isEqualTo("30°36′21″.3N，114°19′22″.2E");
        assertThat(history.path("evidence").path("position_raw").asText()).contains("30°36′21″.3N，114°19′22″.2E");
        assertThat(properties.path("notices").get(0).path("notice_no").asText()).isEqualTo("汉道通〔2026〕210号");
    }

    @Test
    void activeRouteSerializesWithSnakeCaseFields() {
        JsonNode route = mapper.valueToTree(controller().activeRoute());

        assertThat(route.path("route_id").asText()).isEqualTo("wuhan-demo-own");
        assertThat(route.path("legs").get(0).has("xtd_starboard_m")).isTrue();
        assertThat(route.path("waypoints").get(0).has("turn_radius_m")).isTrue();
    }

    @Test
    void noticeAlertPayloadUsesProtocolFieldNames() {
        NoticeAlertPayload payload = NoticeAlertPayload.builder()
                .alertId("a").status(NoticeAlertStatus.ACTIVE).etaSeconds(12L)
                .aton(NoticeAlertPayload.Aton.builder().virtual(true).build())
                .notice(NoticeAlertPayload.Notice.builder().itemSeq(5).build())
                .evidence(Map.of("action", "设置"))
                .build();

        JsonNode json = mapper.valueToTree(payload);

        assertThat(json.has("alert_id")).isTrue();
        assertThat(json.has("clear_reason")).isTrue();
        assertThat(json.path("eta_seconds").asLong()).isEqualTo(12L);
        assertThat(json.path("aton").path("is_virtual").asBoolean()).isTrue();
        assertThat(json.path("notice").path("item_seq").asInt()).isEqualTo(5);
        assertThat(json.has("data_cutoff")).isTrue();
    }
}
