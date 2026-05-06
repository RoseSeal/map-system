package com.whut.map.map_service.llm.agent.tool;

public final class AgentToolNames {
    private AgentToolNames() {}

    public static final String GET_RISK_SNAPSHOT = "get_risk_snapshot";
    public static final String GET_TOP_RISK_TARGETS = "get_top_risk_targets";
    public static final String GET_TARGET_DETAIL = "get_target_detail";
    public static final String GET_OWN_SHIP_STATE = "get_own_ship_state";
    public static final String QUERY_REGULATORY_CONTEXT = "query_regulatory_context";
    public static final String EVALUATE_MANEUVER = "evaluate_maneuver";
    public static final String GET_WEATHER_CONTEXT = "get_weather_context";
    public static final String EVALUATE_MANEUVER_WITH_WEATHER = "evaluate_maneuver_with_weather";
    public static final String QUERY_BATHYMETRY = "query_bathymetry";
    public static final String EVALUATE_MANEUVER_HYDROLOGY = "evaluate_maneuver_hydrology";
}
