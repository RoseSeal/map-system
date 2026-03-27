package com.whut.map.map_service.websocket;

import com.whut.map.map_service.dto.RiskObjectDto;
import com.whut.map.map_service.dto.websocket.BackendMessage;
import com.whut.map.map_service.dto.websocket.WebSocketMessageTypes;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class BackendMessageFactory {

    private final AtomicLong sequenceGenerator = new AtomicLong(System.currentTimeMillis());

    public BackendMessage buildPongMessage() {
        return buildGeneratedMessage(WebSocketMessageTypes.PONG, null);
    }

    public BackendMessage buildRiskUpdateMessage(RiskObjectDto payload) {
        return buildGeneratedMessage(WebSocketMessageTypes.RISK_UPDATE, payload);
    }

    public BackendMessage buildMessage(String type, String sequenceId, Object payload) {
        BackendMessage message = new BackendMessage();
        message.setType(type);
        message.setSequenceId(sequenceId);
        message.setPayload(payload);
        return message;
    }

    private BackendMessage buildGeneratedMessage(String type, Object payload) {
        return buildMessage(type, String.valueOf(sequenceGenerator.getAndIncrement()), payload);
    }
}
