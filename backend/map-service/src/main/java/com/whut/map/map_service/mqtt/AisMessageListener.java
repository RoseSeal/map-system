package com.whut.map.map_service.mqtt;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AisMessageListener implements MqttCallback {
    @Override
    public void connectionLost(Throwable cause) {
        log.error("MQTT connection lost: {}", cause.getMessage(), cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        String payload = new String(message.getPayload());
        log.info("Received MQTT message on topic {}: {}", topic, payload);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 这个方法在客户端发布消息时被调用，通常不需要处理
    }
}
