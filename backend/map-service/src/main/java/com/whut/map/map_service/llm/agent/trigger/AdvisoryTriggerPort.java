package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;

@FunctionalInterface
public interface AdvisoryTriggerPort {
    /**
     * Called when a new advisory should be generated.
     * The implementor MUST call onComplete exactly once when advisory generation
     * finishes (success or failure), so the tracker can accept the next trigger.
     */
    void onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete);
}
