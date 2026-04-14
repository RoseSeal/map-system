package com.whut.map.map_service.risk.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SseEmitterRegistry {

    private final ObjectMapper objectMapper;
    private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

    public void register(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(throwable -> emitters.remove(emitter));
    }

    public void broadcast(SseEventType eventType, String sequenceId, Object payload) {
        if (payload == null) {
            log.warn("Skipping SSE broadcast because payload is null, eventType={}", eventType);
            return;
        }

        String jsonPayload = serialize(payload, eventType);
        if (jsonPayload == null) {
            return;
        }

        broadcastJson(eventType, sequenceId, jsonPayload);
    }

    public void broadcastJson(SseEventType eventType, String sequenceId, String jsonPayload) {
        if (jsonPayload == null) {
            log.warn("Skipping SSE broadcast because json payload is null, eventType={}", eventType);
            return;
        }

        emitters.forEach(emitter -> {
            sendJson(emitter, eventType, sequenceId, jsonPayload);
        });
    }

    public void sendJson(SseEmitter emitter, SseEventType eventType, String sequenceId, String jsonPayload) {
        if (emitter == null || jsonPayload == null) {
            return;
        }

        try {
            emitter.send(SseEmitter.event()
                    .id(sequenceId)
                    .name(eventType.getValue())
                    .data(jsonPayload));
        } catch (Exception e) {
            log.debug("Removing failed SSE emitter after {} send failure: {}", eventType, e.getMessage());
            removeEmitter(emitter);
        }
    }

    public void sendKeepalive() {
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment(" keepalive"));
            } catch (Exception e) {
                log.debug("Removing failed SSE emitter after keepalive failure: {}", e.getMessage());
                removeEmitter(emitter);
            }
        });
    }

    private void removeEmitter(SseEmitter emitter) {
        emitters.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception e) {
            log.trace("Ignoring SSE emitter completion error: {}", e.getMessage());
        }
    }

    public String serialize(Object payload, SseEventType eventType) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("Failed to serialize SSE payload for eventType={}: {}", eventType, e.getMessage());
            return null;
        }
    }
}
