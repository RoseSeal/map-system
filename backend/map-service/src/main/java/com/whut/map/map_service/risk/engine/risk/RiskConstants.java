package com.whut.map.map_service.risk.engine.risk;

import com.whut.map.map_service.shared.domain.RiskLevel;
public final class RiskConstants {
    private RiskConstants() {
    }

    @Deprecated
    public static final String SAFE = RiskLevel.SAFE.name();
    @Deprecated
    public static final String CAUTION = RiskLevel.CAUTION.name();
    @Deprecated
    public static final String WARNING = RiskLevel.WARNING.name();
    @Deprecated
    public static final String ALARM = RiskLevel.ALARM.name();

    public static final String EXPLANATION_SOURCE_RULE = "rule";
    public static final String EXPLANATION_TEXT_AWAITING_CPA = "Awaiting CPA/TCPA";
    public static final String EXPLANATION_TEXT_DERIVED = "CPA/TCPA derived risk";
}
