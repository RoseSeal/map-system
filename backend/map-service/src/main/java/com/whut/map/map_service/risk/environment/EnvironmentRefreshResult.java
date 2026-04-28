package com.whut.map.map_service.risk.environment;

import java.util.List;

public record EnvironmentRefreshResult(
        EnvironmentStateSnapshot snapshot,
        EnvironmentUpdateReason reason,
        List<String> changedFields,
        boolean shouldPublish
) {
}
