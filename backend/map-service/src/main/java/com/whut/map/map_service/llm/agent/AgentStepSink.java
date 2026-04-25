package com.whut.map.map_service.llm.agent;

@FunctionalInterface
public interface AgentStepSink {
    void accept(AgentStepEvent event);

    AgentStepSink NOOP = event -> {};
}
