package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.function.Consumer;

@Component
@ConditionalOnMissingBean(AdvisoryTriggerPort.class)
class NoOpAdvisoryTriggerPort implements AdvisoryTriggerPort {
    @Override
    public void onAdvisoryTrigger(AgentSnapshot snapshot, Consumer<Set<String>> onComplete) {
        onComplete.accept(Set.of()); // no async work; release the flag immediately
    }
}
