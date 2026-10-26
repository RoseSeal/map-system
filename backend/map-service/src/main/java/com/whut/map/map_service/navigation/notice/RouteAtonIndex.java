package com.whut.map.map_service.navigation.notice;

import com.whut.map.map_service.navigation.route.RouteGeometry;
import com.whut.map.map_service.navigation.route.RouteProjection;

import java.util.ArrayList;
import java.util.List;

/**
 * 预计算每个有效航标与航线的关系：沿线位置、到航线距离，以及航线进入 / 离开缓冲圈的沿线位置。
 * 进入 / 离开点把航标附近的航段近似为直线（step-1 §2.5）。
 */
public record RouteAtonIndex(List<Entry> entries) {

    public record Entry(
            AtonRecord aton,
            double alongMeters,
            double lateralMeters,
            double entryMeters,
            double exitMeters
    ) {
    }

    public static RouteAtonIndex build(RouteGeometry geometry, List<AtonRecord> validAtons, double bufferMeters) {
        List<Entry> entries = new ArrayList<>();
        for (AtonRecord aton : validAtons) {
            RouteProjection projection = geometry.project(aton.position().lon(), aton.position().lat());
            double lateral = projection.distanceMeters();
            if (lateral > bufferMeters) {
                continue;
            }
            double halfChord = Math.sqrt(bufferMeters * bufferMeters - lateral * lateral);
            double along = projection.alongMeters();
            entries.add(new Entry(aton, along, lateral, along - halfChord, along + halfChord));
        }
        return new RouteAtonIndex(List.copyOf(entries));
    }
}
