package com.whut.map.map_service.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.navigation.config.NavigationProperties;
import com.whut.map.map_service.navigation.notice.AtonNoticeRepository;
import com.whut.map.map_service.navigation.route.PlannedRouteRepository;
import org.springframework.core.io.DefaultResourceLoader;

public final class NavigationTestSupport {

    public static final String TEST_ROUTE = "classpath:navigation/test-route.json";
    public static final String TEST_ATONS = "classpath:navigation/test-atons.json";

    private NavigationTestSupport() {
    }

    public static NavigationProperties properties(String route, String atons) {
        NavigationProperties properties = new NavigationProperties();
        properties.getRoute().setActiveResource(route);
        properties.getNotice().setAtonStateResource(atons);
        return properties;
    }

    public static NavigationProperties testProperties() {
        return properties(TEST_ROUTE, TEST_ATONS);
    }

    public static NavigationProperties productionProperties() {
        return new NavigationProperties();
    }

    public static PlannedRouteRepository routeRepository(NavigationProperties properties) {
        return new PlannedRouteRepository(properties, new DefaultResourceLoader(), new ObjectMapper());
    }

    public static AtonNoticeRepository atonRepository(NavigationProperties properties) {
        return new AtonNoticeRepository(properties, new DefaultResourceLoader(), new ObjectMapper());
    }
}
