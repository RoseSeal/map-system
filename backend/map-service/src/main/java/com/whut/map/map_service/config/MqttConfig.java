package com.whut.map.map_service.config;

import com.whut.map.map_service.mqtt.AisMessageListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.context.annotation.Configuration;

@Slf4j // 使用 Lombok 的 @Slf4j 注解来自动生成日志记录器
@Configuration
public class MqttConfig {

    private final MqttProperties mqttProperties;
    private AisMessageListener listener;

    public MqttConfig(MqttProperties mqttProperties, AisMessageListener listener) {
        this.mqttProperties = mqttProperties;
        this.listener = listener;
    }

    /**
     * TODO: [Tech Debt - v0.5] Refactor Mqtt Client Initialization
     * 当前使用 @PostConstruct 在主线程中同步建立 MQTT 连接。
     * 如果 Broker (消息中间件) 不可用或网络抖动，会导致 client.connect() 一直阻塞 (Block)，
     * 进而导致整个 Spring Boot 容器启动失败。
     * 后续迭代需改为：
     * 1. 使用 MqttAsyncClient 进行异步非阻塞连接。
     * 2. 或将连接逻辑移入独立的线程池 (Thread Pool) 中执行。
     * 3. 补充 MqttConnectOptions 的重连 (Auto-reconnection) 与容灾机制。
     */
    @PostConstruct
    public void init() throws Exception {

        MqttClient  client = new MqttClient (
                mqttProperties.getBroker(),
                mqttProperties.getClientId()
        );
        MqttConnectOptions options = new MqttConnectOptions();
        if (mqttProperties.getUsername() != null && !mqttProperties.getUsername().isEmpty()) {
            options.setUserName(mqttProperties.getUsername());
        }
        if (mqttProperties.getPassword() != null && !mqttProperties.getPassword().isEmpty()) {
            options.setPassword(mqttProperties.getPassword().toCharArray());
        }
        options.setAutomaticReconnect(true);
        options.setKeepAliveInterval(60);
        options.setCleanSession(true);

        client.setCallback(listener);
        client.connect(options);
        client.subscribe(mqttProperties.getTopic());

        log.info("MQTT client connected to broker: {}, subscribed to topic: {}", mqttProperties.getBroker(), mqttProperties.getTopic());
    }
}
