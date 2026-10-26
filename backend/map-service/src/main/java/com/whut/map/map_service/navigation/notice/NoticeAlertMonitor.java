package com.whut.map.map_service.navigation.notice;

import com.whut.map.map_service.navigation.config.NavigationProperties;
import com.whut.map.map_service.navigation.route.PlannedRoute;
import com.whut.map.map_service.navigation.route.PlannedRouteRepository;
import com.whut.map.map_service.navigation.route.RouteGeometry;
import com.whut.map.map_service.navigation.route.RouteLeg;
import com.whut.map.map_service.navigation.route.RouteProjection;
import com.whut.map.map_service.risk.event.RiskAssessmentCompletedEvent;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertClearReason;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertPayload;
import com.whut.map.map_service.shared.dto.sse.NoticeAlertStatus;
import com.whut.map.map_service.shared.util.GeoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 沿计划航线判断本船前瞻窗口是否经过有效航标缓冲圈，并维护每个航标的接近状态（step-1 §2.5 / §2.6）。
 * 监听在 RiskStreamPublisher 的单线程发布执行器上同步执行，接近状态只在该线程读写。
 */
@Component
@ConditionalOnProperty(prefix = "navigation.notice-alert", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NoticeAlertMonitor {

    private enum Phase { IDLE, ACTIVE, PASSED }

    private static final class Approach {
        private Phase phase = Phase.IDLE;
        private String alertId;
        private Instant lastPublishedAt;
    }

    private final PlannedRouteRepository routeRepository;
    private final AtonNoticeRepository atonRepository;
    private final NavigationProperties properties;
    private final RiskStreamPublisher publisher;
    private final Clock clock;

    private final Map<String, Approach> approaches = new HashMap<>();
    private PlannedRoute indexedRoute;
    private RouteGeometry geometry;
    private RouteAtonIndex index;
    private OffsetDateTime lastOwnShipMsgTime;

    @Autowired
    public NoticeAlertMonitor(
            PlannedRouteRepository routeRepository,
            AtonNoticeRepository atonRepository,
            NavigationProperties properties,
            RiskStreamPublisher publisher
    ) {
        this(routeRepository, atonRepository, properties, publisher, Clock.systemUTC());
    }

    NoticeAlertMonitor(
            PlannedRouteRepository routeRepository,
            AtonNoticeRepository atonRepository,
            NavigationProperties properties,
            RiskStreamPublisher publisher,
            Clock clock
    ) {
        this.routeRepository = routeRepository;
        this.atonRepository = atonRepository;
        this.properties = properties;
        this.publisher = publisher;
        this.clock = clock;
    }

    @EventListener
    public void onRiskAssessmentCompleted(RiskAssessmentCompletedEvent event) {
        ShipStatus ownShip = event == null ? null : event.ownShip();
        if (ownShip == null
                || (ownShip.getMsgTime() != null && Objects.equals(ownShip.getMsgTime(), lastOwnShipMsgTime))) {
            return;
        }
        lastOwnShipMsgTime = ownShip.getMsgTime();

        PlannedRoute route = routeRepository.current();
        if (route != indexedRoute) {
            clearActive(NoticeAlertClearReason.ROUTE_CHANGED);
            approaches.clear();
            indexedRoute = route;
            geometry = RouteGeometry.of(route);
            index = RouteAtonIndex.build(geometry, atonRepository.validAtons(), alertProperties().getBufferM());
        }

        RouteProjection projection = geometry.project(ownShip.getLongitude(), ownShip.getLatitude());
        RouteLeg leg = route.legs().get(projection.legIndex());
        double xtd = projection.signedOffsetMeters() >= 0 ? leg.xtdPortM() : leg.xtdStarboardM();
        // 用到有限航段的真实距离判断偏航：起点之前或终点之后的点垂直偏移可能为 0，
        // 但其截断投影不代表沿线位置，超出偏航限时同样视为不在航线上。
        if (projection.distanceMeters() > xtd) {
            clearActive(NoticeAlertClearReason.OFF_ROUTE);
            return;
        }

        double ownAlong = projection.alongMeters();
        double sogMps = ownShip.getSog() * GeoUtils.METERS_PER_KNOT;
        boolean moving = ownShip.getSog() >= alertProperties().getMinSogKn();
        double lookaheadMeters = sogMps * alertProperties().getLookaheadMinutes() * 60.0;
        Instant now = clock.instant();

        for (RouteAtonIndex.Entry entry : index.entries()) {
            Approach approach = approaches.computeIfAbsent(entry.aton().atonId(), id -> new Approach());
            // 本船回到进入点之前一个缓冲距离以外（如模拟器重跑），视为新的一次接近。
            if (approach.phase == Phase.PASSED && ownAlong < entry.entryMeters() - alertProperties().getBufferM()) {
                approach.phase = Phase.IDLE;
            }
            switch (approach.phase) {
                case PASSED -> {
                }
                case ACTIVE -> {
                    if (ownAlong > entry.exitMeters()) {
                        publish(approach, entry, NoticeAlertStatus.CLEARED,
                                NoticeAlertClearReason.PASSED, ownAlong, sogMps, now);
                        approach.phase = Phase.PASSED;
                    } else if (moving && !now.isBefore(approach.lastPublishedAt.plus(refreshInterval()))) {
                        publish(approach, entry, NoticeAlertStatus.ACTIVE, null, ownAlong, sogMps, now);
                    }
                }
                case IDLE -> {
                    if (ownAlong > entry.exitMeters()) {
                        approach.phase = Phase.PASSED;
                    } else if (moving && entry.entryMeters() <= ownAlong + lookaheadMeters) {
                        approach.phase = Phase.ACTIVE;
                        approach.alertId = "notice-alert-" + UUID.randomUUID();
                        publish(approach, entry, NoticeAlertStatus.ACTIVE, null, ownAlong, sogMps, now);
                    }
                }
            }
        }
    }

    private void clearActive(NoticeAlertClearReason reason) {
        if (index == null) {
            return;
        }
        Instant now = clock.instant();
        for (RouteAtonIndex.Entry entry : index.entries()) {
            Approach approach = approaches.get(entry.aton().atonId());
            if (approach != null && approach.phase == Phase.ACTIVE) {
                publish(approach, entry, NoticeAlertStatus.CLEARED, reason, Double.NaN, 0.0, now);
                approach.phase = Phase.IDLE;
            }
        }
    }

    private void publish(
            Approach approach,
            RouteAtonIndex.Entry entry,
            NoticeAlertStatus status,
            NoticeAlertClearReason clearReason,
            double ownAlong,
            double sogMps,
            Instant now
    ) {
        AtonRecord aton = entry.aton();
        AtonRecord.HistoryEntry latest = aton.latest();
        Long eta = null;
        double distance = 0.0;
        if (status == NoticeAlertStatus.ACTIVE) {
            distance = Math.max(0.0, entry.entryMeters() - ownAlong);
            eta = sogMps > 0 ? Math.round(distance / sogMps) : null;
        }
        NoticeAlertPayload payload = NoticeAlertPayload.builder()
                .eventId(UUID.randomUUID().toString())
                .alertId(approach.alertId)
                .status(status)
                .clearReason(clearReason)
                .timestamp(now.toString())
                .routeId(indexedRoute.routeId())
                .aton(NoticeAlertPayload.Aton.builder()
                        .atonId(aton.atonId())
                        .name(aton.name())
                        .waterway(aton.waterway())
                        .virtual(aton.virtual())
                        .state(aton.state().name())
                        .lastAction(aton.lastAction())
                        .effectiveAt(aton.effectiveAt())
                        .lon(aton.position().lon())
                        .lat(aton.position().lat())
                        .build())
                .distanceAlongRouteM(distance)
                .lateralOffsetM(entry.lateralMeters())
                .etaSeconds(eta)
                .bufferM(alertProperties().getBufferM())
                .lookaheadMinutes(alertProperties().getLookaheadMinutes())
                .notice(latest == null ? null : buildNotice(latest))
                .evidence(latest == null ? Map.of() : latest.evidence())
                .dataCutoff(atonRepository.dataCutoff())
                .build();
        approach.lastPublishedAt = now;
        publisher.publishNoticeAlert(payload);
    }

    private NoticeAlertPayload.Notice buildNotice(AtonRecord.HistoryEntry latest) {
        NoticeAlertPayload.Notice.NoticeBuilder builder = NoticeAlertPayload.Notice.builder()
                .noticeId(latest.noticeId())
                .itemSeq(latest.itemSeq());
        atonRepository.findNotice(latest.noticeId()).ifPresent(meta -> builder
                .noticeNo(meta.noticeNo())
                .title(meta.title())
                .publishedAt(meta.publishedAt())
                .url(meta.url()));
        return builder.build();
    }

    private Duration refreshInterval() {
        return Duration.ofSeconds(alertProperties().getRefreshSeconds());
    }

    private NavigationProperties.NoticeAlert alertProperties() {
        return properties.getNoticeAlert();
    }
}
