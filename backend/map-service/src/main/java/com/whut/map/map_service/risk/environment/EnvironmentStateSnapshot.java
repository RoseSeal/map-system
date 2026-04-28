package com.whut.map.map_service.risk.environment;

import java.util.Map;

public record EnvironmentStateSnapshot(
        long version,
        String timestamp,
        Map<String, Object> environmentContext
) {
}
