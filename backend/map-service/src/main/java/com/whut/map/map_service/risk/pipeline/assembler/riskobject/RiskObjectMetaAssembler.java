package com.whut.map.map_service.risk.pipeline.assembler.riskobject;

import com.whut.map.map_service.risk.config.RiskObjectMetaProperties;
import com.whut.map.map_service.shared.domain.ShipStatus;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Map;

@Component
public class RiskObjectMetaAssembler {

    private final RiskObjectMetaProperties properties;

    public RiskObjectMetaAssembler(RiskObjectMetaProperties properties) {
        this.properties = properties;
    }

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

    public Map<String, Object> buildGovernance(double trustFactor) {
        return Map.of("mode", properties.getGovernanceMode(), "trust_factor", trustFactor);
    }
}
