package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.tracking.store.DerivedTargetStateStore;
import com.whut.map.map_service.tracking.store.ShipStateStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Component
public class AgentSnapshotFactory {

    private final RiskContextHolder riskContextHolder;
    private final DerivedTargetStateStore derivedTargetStateStore;
    private final LlmRiskContextDeepCopier contextCopier;
    private final TargetStateSnapshotDeepCopier targetCopier;
    private final ShipStateStore shipStateStore;

    @Autowired
    public AgentSnapshotFactory(RiskContextHolder riskContextHolder, DerivedTargetStateStore derivedTargetStateStore,
                                LlmRiskContextDeepCopier contextCopier, TargetStateSnapshotDeepCopier targetCopier,
                                ShipStateStore shipStateStore) {
        this.riskContextHolder = riskContextHolder;
        this.derivedTargetStateStore = derivedTargetStateStore;
        this.contextCopier = contextCopier;
        this.targetCopier = targetCopier;
        this.shipStateStore = shipStateStore;
    }

    public AgentSnapshotFactory(RiskContextHolder riskContextHolder, DerivedTargetStateStore derivedTargetStateStore,
                                LlmRiskContextDeepCopier contextCopier, TargetStateSnapshotDeepCopier targetCopier) {
        this(riskContextHolder, derivedTargetStateStore, contextCopier, targetCopier, null);
    }

    public AgentSnapshot build() {
        RiskContextHolder.Snapshot snapshot = riskContextHolder.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("No risk context snapshot available; system not yet initialized");
        }
        var frozenCtx = contextCopier.copy(snapshot.context());
        var frozenTargets = targetCopier.copyAll(derivedTargetStateStore.getAll());

        ShipStatus ownShip = shipStateStore.getOwnShip();
        ShipStatus frozenOwnShip = ownShip != null ? shallowCopy(ownShip) : null;

        Map<String, ShipStatus> frozenTargetShips = buildFrozenTargetShips();

        return new AgentSnapshot(snapshot.version(), frozenCtx, frozenTargets, frozenOwnShip, frozenTargetShips);
    }

    private Map<String, ShipStatus> buildFrozenTargetShips() {
        Map<String, ShipStatus> allShips = shipStateStore.getAll();
        if (allShips == null || allShips.isEmpty()) {
            return Collections.emptyMap();
        }
        ShipStatus ownShip = shipStateStore.getOwnShip();
        String ownShipId = ownShip != null ? ownShip.getId() : null;

        Map<String, ShipStatus> result = new HashMap<>();
        allShips.forEach((id, ship) -> {
            if (ship != null && !id.equals(ownShipId)) {
                result.put(id, shallowCopy(ship));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private ShipStatus shallowCopy(ShipStatus source) {
        return ShipStatus.builder()
                .id(source.getId())
                .role(source.getRole())
                .longitude(source.getLongitude())
                .latitude(source.getLatitude())
                .sog(source.getSog())
                .cog(source.getCog())
                .heading(source.getHeading())
                .msgTime(source.getMsgTime())
                .confidence(source.getConfidence())
                .qualityFlags(source.getQualityFlags())
                .build();
    }
}
