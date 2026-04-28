package com.whut.map.map_service.llm.client;

import com.whut.map.map_service.llm.config.LlmProperties;
import lombok.Getter;
import org.springframework.stereotype.Component;

@Component
public class LlmProviderSelectionStore {

    @Getter
    private LlmProvider explanationProvider;
    @Getter
    private LlmProvider chatProvider;

    public LlmProviderSelectionStore(LlmProperties llmProperties) {
        this.explanationProvider = llmProperties.resolveExplanationProvider();
        this.chatProvider = llmProperties.resolveChatProvider();
    }

    public synchronized LlmProvider getSelection(LlmTaskType taskType) {
        return switch (taskType) {
            case EXPLANATION -> explanationProvider;
            case CHAT, AGENT -> chatProvider;
        };
    }

    public synchronized void updateSelection(LlmTaskType taskType, LlmProvider provider) {
        switch (taskType) {
            case EXPLANATION -> explanationProvider = provider;
            case CHAT, AGENT -> chatProvider = provider;
        }
    }
}
