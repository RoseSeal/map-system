package com.whut.map.map_service.source.weather;

import com.whut.map.map_service.source.weather.dto.WeatherZoneContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class RegionalWeatherResolver {

    public Optional<WeatherZoneContext> resolve(double lat, double lon, List<WeatherZoneContext> zones) {
        if (zones == null || zones.isEmpty()) {
            return Optional.empty();
        }
        for (WeatherZoneContext zone : zones) {
            if (zone.geometry() == null) {
                continue;
            }
            if (containsPoint(lon, lat, zone.geometry())) {
                return Optional.of(zone);
            }
        }
        return Optional.empty();
    }

    private boolean containsPoint(double lon, double lat, WeatherZoneContext.ZoneGeometry geometry) {
        String type = geometry.type();
        if ("Polygon".equals(type)) {
            return polygonContains(lon, lat, geometry.coordinates());
        } else if ("MultiPolygon".equals(type)) {
            return multiPolygonContains(lon, lat, geometry.coordinates());
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean polygonContains(double lon, double lat, Object coordinates) {
        try {
            List<List<List<Double>>> rings = (List<List<List<Double>>>) coordinates;
            if (rings == null || rings.isEmpty()) {
                return false;
            }
            return rayCasting(lon, lat, rings.get(0));
        } catch (ClassCastException e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean multiPolygonContains(double lon, double lat, Object coordinates) {
        try {
            List<List<List<List<Double>>>> polygons = (List<List<List<List<Double>>>>) coordinates;
            if (polygons == null) {
                return false;
            }
            for (List<List<List<Double>>> polygon : polygons) {
                if (polygon != null && !polygon.isEmpty() && rayCasting(lon, lat, polygon.get(0))) {
                    return true;
                }
            }
        } catch (ClassCastException e) {
            return false;
        }
        return false;
    }

    private boolean rayCasting(double lon, double lat, List<List<Double>> ring) {
        int n = ring.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i).get(0);
            double yi = ring.get(i).get(1);
            double xj = ring.get(j).get(0);
            double yj = ring.get(j).get(1);
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }
}
