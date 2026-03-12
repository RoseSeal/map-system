package com.whut.map.map_service.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whut.map.map_service.dto.RiskObjectDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Component
public class AisWebSocketService {

    private final StreamWebSocketHandler webSocketHandler;
    private final ObjectMapper objectMapper;

    private final AtomicLong messageCounter = new AtomicLong(1);

    public AisWebSocketService(StreamWebSocketHandler webSocketHandler, ObjectMapper objectMapper) {
        this.webSocketHandler = webSocketHandler;
        this.objectMapper = objectMapper;
    }

    public void sendAisMessage(RiskObjectDto message) {
        try {
            // 组装符合前端需求的信封
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("type", "RISK_UPDATE");
            envelope.put("sequence_id", messageCounter.getAndIncrement());
            envelope.put("payload", message);

            // 将信封对象序列化为 JSON 字符串
            String jsonMessage = objectMapper.writeValueAsString(envelope);

            // 发送 JSON 字符串到 WebSocket 客户端
            webSocketHandler.broadcastMessage(jsonMessage);

        } catch (Exception e) {
            log.error("Error serializing AIS message: {}", e.getMessage());
        }
    }

}
