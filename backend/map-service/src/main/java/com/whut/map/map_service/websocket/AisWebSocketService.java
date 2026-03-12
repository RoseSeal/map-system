package com.whut.map.map_service.websocket;

import com.whut.map.map_service.domain.AisMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AisWebSocketService {

    private static final String TOPIC_AIS  = "/topic/ais";
    private static final String TOPIC_CV_PREDICTION = "/topic/cv-prediction";
    private static final String TOPIC_CPA_TCPA = "/topic/cpa-tcpa";
    private static final String TOPIC_SHIP_DOMAIN = "/topic/ship-domain";

    private void sendMessage(String topic, Object payload) {
        // 1. 将数据发送到WebSocket客户端
        log.debug("Sending message to WebSocket topic: {}, payload: {}", topic, payload);
    }

    public void sentAisMessage(AisMessage message) {
        sendMessage(TOPIC_AIS, message);
    }

    public void sentCvPrediction(Object prediction) {
        sendMessage(TOPIC_CV_PREDICTION, prediction);
    }

    public void sentCpaTcpa(Object cpaTcpa) {
        sendMessage(TOPIC_CPA_TCPA, cpaTcpa);
    }

    public void sentShipDomain(Object shipDomain) {
        sendMessage(TOPIC_SHIP_DOMAIN, shipDomain);
    }
}
