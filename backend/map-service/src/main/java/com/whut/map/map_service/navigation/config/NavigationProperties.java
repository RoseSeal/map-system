package com.whut.map.map_service.navigation.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "navigation")
public class NavigationProperties {

    private Route route = new Route();
    private Notice notice = new Notice();
    private NoticeAlert noticeAlert = new NoticeAlert();

    @Data
    public static class Route {
        private String activeResource = "classpath:routes/wuhan-demo-own.route.json";
    }

    @Data
    public static class Notice {
        private String atonStateResource = "classpath:notices/aton_state.json";
    }

    @Data
    public static class NoticeAlert {
        private boolean enabled = true;
        private double bufferM = 200.0;
        private double lookaheadMinutes = 60.0;
        private double minSogKn = 0.5;
        private long refreshSeconds = 30;
    }
}
