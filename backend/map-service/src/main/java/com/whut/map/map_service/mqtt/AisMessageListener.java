package com.whut.map.map_service.mqtt;

import com.whut.map.map_service.domain.ShipStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.pipeline.ShipDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AisMessageListener implements MqttCallback {

    private ObjectMapper objectMapper;
    private final AisMessageMapper mapper;
    private ShipDispatcher dispatcher;

    public AisMessageListener(AisMessageMapper mapper, ObjectMapper objectMapper, ShipDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.mapper = mapper;
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.error("MQTT connection lost: {}", cause.getMessage(), cause);
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        try {
            String payload = new String(message.getPayload());
            log.debug("payload: {}", payload);
            MqttAisDto aisDto = objectMapper.readValue(payload, MqttAisDto.class);
            ShipStatus aisMessage = mapper.toDomain(aisDto);
            log.debug("已解析Ais数据: {}", aisMessage);
            dispatcher.dispatch(aisMessage);
        } catch (Exception e) {
            log.error("Failed to parse MQTT message payload", e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // 这个方法在客户端发布消息时被调用，目前暂时仅接收数据
    }
}
