package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import java.util.Set;
import java.util.function.Consumer;

@FunctionalInterface
public interface AdvisoryTriggerPort {
    /**
     * Called when a new advisory should be generated.
     * The implementor MUST call onComplete exactly once when advisory generation
     * finishes. Pass published affected target IDs on success, or an empty set on failure.
     */
    void onAdvisoryTrigger(AgentSnapshot snapshot, Consumer<Set<String>> onComplete);
}
