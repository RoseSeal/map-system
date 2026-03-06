package com.whut.map.listener.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "mqtt")
// 提取MQTT配置
public class MqttProperties {
    private String broker;
    private String clientId;
    private String username;
    private String password;
    private List<String> topics;

    public String[] getTopics() {
        return topics.toArray(new String[0]);
    }
}
