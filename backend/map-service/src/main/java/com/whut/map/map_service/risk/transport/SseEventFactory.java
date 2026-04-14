package com.whut.map.map_service.risk.transport;

import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.shared.dto.sse.RiskUpdatePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SseEventFactory {

    public String generateEventId() {
        return UUID.randomUUID().toString();
    }

    public RiskUpdatePayload buildRiskUpdate(RiskObjectDto riskObject) {
        if (riskObject == null) {
            return null;
        }

        return RiskUpdatePayload.builder()
                .eventId(generateEventId())
                .riskObjectId(riskObject.getRiskObjectId())
                .timestamp(riskObject.getTimestamp())
                .governance(riskObject.getGovernance())
                .ownShip(riskObject.getOwnShip())
                .targets(riskObject.getTargets())
                .environmentContext(riskObject.getEnvironmentContext())
                .build();
    }
}
