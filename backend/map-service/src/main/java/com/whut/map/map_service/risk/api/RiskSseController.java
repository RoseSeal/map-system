package com.whut.map.map_service.risk.api;

import com.whut.map.map_service.shared.transport.protocol.ProtocolPaths;
import com.whut.map.map_service.risk.transport.RiskStreamPublisher;
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

    private final RiskStreamPublisher riskStreamPublisher;

    @GetMapping(value = ProtocolPaths.RISK_STREAM, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRisk() {
        SseEmitter emitter = new SseEmitter(0L);
        riskStreamPublisher.register(emitter);
        return emitter;
    }
}
