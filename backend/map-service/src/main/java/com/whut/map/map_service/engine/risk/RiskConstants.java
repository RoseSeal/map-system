package com.whut.map.map_service.engine.risk;

public final class RiskConstants {
    private RiskConstants() {
    }

    public static final String SAFE = "SAFE";
    public static final String CAUTION = "CAUTION";
    public static final String WARNING = "WARNING";
    public static final String ALARM = "ALARM";

    public static final String EXPLANATION_SOURCE_RULE = "rule";
    public static final String EXPLANATION_SOURCE_LLM = "llm";
    public static final String EXPLANATION_SOURCE_FALLBACK = "fallback";
    public static final String EXPLANATION_TEXT_AWAITING_CPA = "Awaiting CPA/TCPA";
    public static final String EXPLANATION_TEXT_DERIVED = "CPA/TCPA derived risk";
}

