package com.whut.map.map_service.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ship.state")
public class ShipStateProperties {

    /**
     * 船舶状态超过多久没有新消息就视为过期。
     */
    private long expireAfterSeconds = 300L;

    /**
     * 写入路径上多久最多做一次过期清理。
     */
    private long cleanupIntervalSeconds = 30L;
}
