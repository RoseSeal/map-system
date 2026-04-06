package com.whut.map.map_service.config.mqtt;

import com.whut.map.map_service.config.properties.MqttProperties;
import com.whut.map.map_service.mqtt.AisMessageListener;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.context.annotation.Configuration;

// 原计划使用 Spring Integration ，但 Spring Integration 适用于复杂消息集成场景；
// 对于简单实时流处理，直接使用客户端库（如 Paho）可以减少框架复杂度并提高可控性。
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
        try {
            MemoryPersistence memoryPersistence = new MemoryPersistence();
            MqttClient  client = new MqttClient (
                    mqttProperties.getBroker(),
                    mqttProperties.getClientId(),
                    memoryPersistence
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
        } catch (MqttException e) {
            log.info("Failed to connect MQTT client to broker: {}, error: {}", mqttProperties.getBroker(), e.getMessage());
        }
    }
}

