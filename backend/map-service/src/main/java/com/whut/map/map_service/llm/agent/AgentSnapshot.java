package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.dto.LlmRiskContext;
import com.whut.map.map_service.shared.domain.ShipStatus;
import com.whut.map.map_service.tracking.store.TargetDerivedSnapshot;

import java.util.Map;

public record AgentSnapshot(
        long snapshotVersion,
        LlmRiskContext riskContext,
        Map<String, TargetDerivedSnapshot> targetDetails,
        ShipStatus frozenOwnShip,
        Map<String, ShipStatus> frozenTargetShips
) {
    public AgentSnapshot(long snapshotVersion, LlmRiskContext riskContext, Map<String, TargetDerivedSnapshot> targetDetails) {
        this(snapshotVersion, riskContext, targetDetails, null, null);
    }
}
