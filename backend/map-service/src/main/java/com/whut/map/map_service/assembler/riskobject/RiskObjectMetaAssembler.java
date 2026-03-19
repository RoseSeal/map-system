package com.whut.map.map_service.assembler.riskobject;

import com.whut.map.map_service.domain.ShipStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
public class RiskObjectMetaAssembler {

    public String buildSnapshotTimestamp(Collection<ShipStatus> allShips, ShipStatus fallback) {
        OffsetDateTime latest = fallback == null ? null : fallback.getMsgTime();
        if (allShips != null) {
            for (ShipStatus ship : allShips) {
                if (ship == null || ship.getMsgTime() == null) {
                    continue;
                }
                if (latest == null || ship.getMsgTime().isAfter(latest)) {
                    latest = ship.getMsgTime();
                }
            }
        }
        return latest == null ? OffsetDateTime.now().toInstant().toString() : latest.toInstant().toString();
    }

    public String buildRiskObjectId(ShipStatus ownShip, String snapshotTimestamp) {
        return ownShip.getId() + "-" + snapshotTimestamp;
    }

    public Map<String, Object> buildGovernance() {
        return Map.of("mode", "adaptive", "trust_factor", 0.99);
    }

    public Map<String, Object> buildEnvironmentContext() {
        return Map.of(
                "safety_contour_val", 10.0,
                "active_alerts", List.of()
        );
    }
}
