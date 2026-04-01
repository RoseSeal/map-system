package com.whut.map.map_service.api;

import com.whut.map.map_service.websocket.ProtocolPaths;
import com.whut.map.map_service.websocket.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RiskSseController {

    private final SseEmitterRegistry sseEmitterRegistry;

    @GetMapping(value = ProtocolPaths.RISK_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRisk() {
        SseEmitter emitter = new SseEmitter(0L);
        sseEmitterRegistry.register(emitter);
        return emitter;
    }
}
