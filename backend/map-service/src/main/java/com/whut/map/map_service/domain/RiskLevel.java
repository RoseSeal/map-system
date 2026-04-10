package com.whut.map.map_service.domain;

public enum RiskLevel {
    SAFE,
    CAUTION,
    WARNING,
    ALARM;

    public static RiskLevel fromValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (RiskLevel riskLevel : values()) {
            if (riskLevel.name().equalsIgnoreCase(value)) {
                return riskLevel;
            }
        }
        return null;
    }
}
