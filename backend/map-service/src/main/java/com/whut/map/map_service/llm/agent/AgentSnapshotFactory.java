package com.whut.map.map_service.llm.agent;

import com.whut.map.map_service.llm.context.RiskContextHolder;
import com.whut.map.map_service.tracking.store.DerivedTargetStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AgentSnapshotFactory {

    private final RiskContextHolder riskContextHolder;
    private final DerivedTargetStateStore derivedTargetStateStore;
    private final LlmRiskContextDeepCopier contextCopier;
    private final TargetStateSnapshotDeepCopier targetCopier;

    public AgentSnapshot build() {
        RiskContextHolder.Snapshot snapshot = riskContextHolder.getSnapshot();
        if (snapshot == null) {
            throw new IllegalStateException("No risk context snapshot available; system not yet initialized");
        }
        var frozenCtx = contextCopier.copy(snapshot.context());
        var frozenTargets = targetCopier.copyAll(derivedTargetStateStore.getAll());
        return new AgentSnapshot(snapshot.version(), frozenCtx, frozenTargets);
    }
}
