package com.whut.map.map_service.llm.agent.trigger;

import com.whut.map.map_service.llm.agent.AgentSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(AdvisoryTriggerPort.class)
class NoOpAdvisoryTriggerPort implements AdvisoryTriggerPort {
    @Override
    public void onAdvisoryTrigger(AgentSnapshot snapshot, Runnable onComplete) {
        onComplete.run(); // no async work; release the flag immediately
    }
}
