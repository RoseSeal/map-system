package com.whut.map.map_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mqtt")
public class MqttProperties {
    private String broker;
    private String clientId;

    private String username; // 允许用户名为空，表示不使用认证
    private String password; // 允许密码为空，表示不使用认证

    private String topic;
}
