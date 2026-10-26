package com.whut.map.map_service.navigation.notice;

import com.whut.map.map_service.navigation.NavigationTestSupport;
import com.whut.map.map_service.navigation.config.NavigationProperties;
import com.whut.map.map_service.risk.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import com.whut.map.map_service.shared.domain.ShipRole;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertClearReason;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertPayload;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 测试航线沿 114.3E 正北 30.58→30.60；valid-near 在 30.59 东侧（右舷）约 100 m，
 * 缓冲 200 m 时进入点约 940 m、离开点约 1287 m（step-1 §2.5 / §2.6）。
 */
class NoticeAlertMonitorTest {

    private static final double METERS_PER_DEGREE = 111320.0;
    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 10, 26, 10, 0, 0, 0, ZoneOffset.ofHours(8));

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-26T02:00:00Z");

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private RiskStreamPublisher publisher;
    private MutableClock clock;
    private NoticeAlertMonitor monitor;
    private int messageSeconds;

    @BeforeEach
    void setUp() {
        NavigationProperties properties = NavigationTestSupport.testProperties();
        publisher = mock(RiskStreamPublisher.class);
        clock = new MutableClock();
        monitor = new NoticeAlertMonitor(
                NavigationTestSupport.routeRepository(properties),
                NavigationTestSupport.atonRepository(properties),
                properties,
                publisher,
                clock);
    }

    private void update(double alongMeters, double sogKn) {
        update(alongMeters, 0.0, sogKn);
    }

    private void update(double alongMeters, double eastMeters, double sogKn) {
        messageSeconds++;
        double lat = 30.58 + alongMeters / METERS_PER_DEGREE;
        double lon = 114.3 + eastMeters / (Math.cos(Math.toRadians(30.58)) * METERS_PER_DEGREE);
        ShipStatus own = ShipStatus.builder()
                .id("ownShip").role(ShipRole.OWN_SHIP)
                .longitude(lon).latitude(lat).sog(sogKn).cog(0.0)
                .msgTime(T0.plusSeconds(messageSeconds))
                .build();
        monitor.onRiskAssessmentCompleted(new RiskAssessmentCompletedEvent(
                messageSeconds, own, List.of(own), Map.of(), null, "risk-" + messageSeconds, false));
    }

    private List<NoticeAlertPayload> published() {
        ArgumentCaptor<NoticeAlertPayload> captor = ArgumentCaptor.forClass(NoticeAlertPayload.class);
        verify(publisher, atLeast(0)).publishNoticeAlert(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void approachPublishesOneActiveAlertWithEtaAndProvenance() {
        update(0, 10);

        List<NoticeAlertPayload> alerts = published();
        assertThat(alerts).hasSize(1);
        NoticeAlertPayload alert = alerts.get(0);
        assertThat(alert.getStatus()).isEqualTo(NoticeAlertStatus.ACTIVE);
        assertThat(alert.getAton().getAtonId()).isEqualTo("valid-near");
        assertThat(alert.getLateralOffsetM()).isCloseTo(100.0, within(1.0));
        double entry = 0.01 * METERS_PER_DEGREE - Math.sqrt(200 * 200 - alert.getLateralOffsetM() * alert.getLateralOffsetM());
        assertThat(alert.getDistanceAlongRouteM()).isCloseTo(entry, within(1.0));
        assertThat(alert.getEtaSeconds()).isCloseTo(Math.round(entry / (10 * GeoUtils.METERS_PER_KNOT)), within(1L));
        assertThat(alert.getNotice().getNoticeNo()).isEqualTo("汉道通〔2026〕210号");
        assertThat(alert.getNotice().getItemSeq()).isEqualTo(5);
        assertThat(alert.getEvidence()).containsEntry("action", "设置");
        assertThat(alert.getDataCutoff()).isEqualTo("2026-10-23");
    }

    @Test
    void repeatedMessageTimeIsSkipped() {
        update(0, 10);
        messageSeconds--;
        update(10, 10);

        assertThat(published()).hasSize(1);
    }

    @Test
    void activeAlertRefreshesWithSameIdOnlyAfterInterval() {
        update(0, 10);
        clock.advanceSeconds(10);
        update(50, 10);
        clock.advanceSeconds(20);
        update(100, 10);

        List<NoticeAlertPayload> alerts = published();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(1).getAlertId()).isEqualTo(alerts.get(0).getAlertId());
        assertThat(alerts.get(1).getEtaSeconds()).isLessThan(alerts.get(0).getEtaSeconds());
    }

    @Test
    void passingClearsOnceAndRerunFromStartAlertsAgain() {
        update(0, 10);
        update(1300, 10);
        update(1400, 10);
        update(0, 10);

        List<NoticeAlertPayload> alerts = published();
        assertThat(alerts).extracting(NoticeAlertPayload::getStatus)
                .containsExactly(NoticeAlertStatus.ACTIVE, NoticeAlertStatus.CLEARED, NoticeAlertStatus.ACTIVE);
        assertThat(alerts.get(1).getClearReason()).isEqualTo(NoticeAlertClearReason.PASSED);
        assertThat(alerts.get(1).getAlertId()).isEqualTo(alerts.get(0).getAlertId());
        assertThat(alerts.get(2).getAlertId()).isNotEqualTo(alerts.get(0).getAlertId());
    }

    @Test
    void leavingRouteBeyondXtdClearsActiveAlert() {
        update(0, 10);
        update(100, -200, 10);

        List<NoticeAlertPayload> alerts = published();
        assertThat(alerts).hasSize(2);
        assertThat(alerts.get(1).getStatus()).isEqualTo(NoticeAlertStatus.CLEARED);
        assertThat(alerts.get(1).getClearReason()).isEqualTo(NoticeAlertClearReason.OFF_ROUTE);
    }

    @Test
    void positionBeyondRouteEndpointIsOffRouteEvenWithZeroPerpendicularOffset() {
        // 起点正后方 1 km：到首段延长线的垂直偏移为 0，但到有限航段的距离为 1 km
        update(-1000, 10);

        verify(publisher, never()).publishNoticeAlert(org.mockito.ArgumentMatchers.any());

        update(0, 10);
        update(-1000, 10);

        List<NoticeAlertPayload> alerts = published();
        assertThat(alerts).extracting(NoticeAlertPayload::getStatus)
                .containsExactly(NoticeAlertStatus.ACTIVE, NoticeAlertStatus.CLEARED);
        assertThat(alerts.get(1).getClearReason()).isEqualTo(NoticeAlertClearReason.OFF_ROUTE);
    }

    @Test
    void lowSpeedDoesNotStartAlert() {
        update(0, 0.3);

        verify(publisher, never()).publishNoticeAlert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lookaheadLimitsAlertsToReachableEntry() {
        // 2 kn × 1 min ≈ 62 m 前瞻：起点处尚不可达，进入点前 50 m 时可达
        NavigationProperties properties = NavigationTestSupport.testProperties();
        properties.getNoticeAlert().setLookaheadMinutes(1);
        monitor = new NoticeAlertMonitor(
                NavigationTestSupport.routeRepository(properties),
                NavigationTestSupport.atonRepository(properties),
                properties, publisher, clock);

        update(0, 2);
        assertThat(published()).isEmpty();
        update(890, 2);
        assertThat(published()).hasSize(1);
    }

    @Test
    void nonValidAndOutOfBufferAidsNeverAlert() {
        for (double along = 0; along <= 2200; along += 100) {
            update(along, 10);
        }

        assertThat(published()).extracting(alert -> alert.getAton().getAtonId())
                .containsOnly("valid-near");
    }
}
