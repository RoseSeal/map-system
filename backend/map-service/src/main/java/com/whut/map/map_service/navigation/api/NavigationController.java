package com.whut.map.map_service.navigation.api;

import com.whut.map.map_service.navigation.notice.AtonNoticeDataset;
import com.whut.map.map_service.navigation.notice.AtonNoticeRepository;
import com.whut.map.map_service.navigation.notice.AtonRecord;
import com.whut.map.map_service.navigation.route.PlannedRoute;
import com.whut.map.map_service.navigation.route.PlannedRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 计划航线与航标通告 REST 接口（v1.3 step-1 §3.3）。
 */
@RestController
@RequestMapping("/api/v2/navigation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class NavigationController {

    private final PlannedRouteRepository routeRepository;
    private final AtonNoticeRepository atonRepository;

    @GetMapping("/route/active")
    public PlannedRoute activeRoute() {
        return routeRepository.current();
    }

    @GetMapping("/notices/aton")
    public Map<String, Object> atonNotices() {
        AtonNoticeDataset dataset = atonRepository.dataset();
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("type", "FeatureCollection");
        collection.put("coverage", dataset.coverage());
        collection.put("assumptions", dataset.assumptions());
        collection.put("features", atonRepository.atons().stream().map(this::toFeature).toList());
        return collection;
    }

    private Map<String, Object> toFeature(AtonRecord aton) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("aton_id", aton.atonId());
        properties.put("waterway", aton.waterway());
        properties.put("name", aton.name());
        properties.put("is_virtual", aton.virtual());
        properties.put("state", aton.state());
        properties.put("last_action", aton.lastAction());
        properties.put("effective_at", aton.effectiveAt());
        properties.put("history", aton.history());
        properties.put("notices", referencedNotices(aton));

        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", aton.atonId());
        feature.put("geometry", Map.of(
                "type", "Point",
                "coordinates", List.of(aton.position().lon(), aton.position().lat())));
        feature.put("properties", properties);
        return feature;
    }

    private List<AtonNoticeDataset.NoticeMeta> referencedNotices(AtonRecord aton) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        aton.history().forEach(entry -> ids.add(entry.noticeId()));
        return ids.stream()
                .map(atonRepository::findNotice)
                .flatMap(Optional::stream)
                .toList();
    }
}
