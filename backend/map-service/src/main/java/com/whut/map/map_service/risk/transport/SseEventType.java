package com.whut.map.map_service.risk.transport;

public enum SseEventType {
    RISK_UPDATE("RISK_UPDATE"),
    ENVIRONMENT_UPDATE("ENVIRONMENT_UPDATE"),
    EXPLANATION("EXPLANATION"),
    ERROR("ERROR"),
    ADVISORY("ADVISORY");

    private final String value;

    SseEventType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
