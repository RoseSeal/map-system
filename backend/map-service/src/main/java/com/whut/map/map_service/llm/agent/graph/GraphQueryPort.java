package com.whut.map.map_service.llm.agent.graph;

public interface GraphQueryPort {
    RegulatoryContext findRegulatoryContext(RegulatoryQuery query);
}
