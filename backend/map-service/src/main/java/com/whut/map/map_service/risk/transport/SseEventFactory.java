package com.whut.map.map_service.risk.transport;

import com.whut.map.map_service.risk.environment.EnvironmentStateSnapshot;
import com.whut.map.map_service.risk.environment.EnvironmentUpdateReason;
import com.whut.map.map_service.shared.dto.RiskObjectDto;
import com.whut.map.map_service.shared.dto.sse.EnvironmentUpdatePayload;
import com.whut.map.map_service.shared.dto.sse.RiskUpdatePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.List;

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
                .environmentStateVersion(riskObject.getEnvironmentStateVersion())
                .governance(riskObject.getGovernance())
                .ownShip(riskObject.getOwnShip())
                .targets(riskObject.getTargets())
                .build();
    }

    public EnvironmentUpdatePayload buildEnvironmentUpdate(
            EnvironmentStateSnapshot snapshot,
            EnvironmentUpdateReason reason,
            List<String> changedFields
    ) {
        if (snapshot == null) {
            return null;
        }

        return EnvironmentUpdatePayload.builder()
                .eventId(generateEventId())
                .timestamp(snapshot.timestamp())
                .environmentStateVersion(snapshot.version())
                .reason(reason)
                .changedFields(changedFields)
                .environmentContext(snapshot.environmentContext())
                .build();
    }
}
