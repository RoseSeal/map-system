package com.whut.map.map_service.llm.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class LlmClientRegistry {

    private static final Map<LlmProvider, EnumSet<LlmTaskType>> SUPPORTED_TASKS = Map.of(
            LlmProvider.GEMINI, EnumSet.of(LlmTaskType.EXPLANATION, LlmTaskType.CHAT, LlmTaskType.AGENT),
            LlmProvider.ZHIPU, EnumSet.of(LlmTaskType.EXPLANATION, LlmTaskType.CHAT, LlmTaskType.AGENT)
    );

    private static final Map<LlmProvider, EnumSet<LlmTaskType>> DEGRADED_TASKS = Map.of(
            LlmProvider.GEMINI, EnumSet.noneOf(LlmTaskType.class),
            LlmProvider.ZHIPU, EnumSet.of(LlmTaskType.AGENT)
    );

    private final EnumMap<LlmProvider, LlmClient> providerClients = new EnumMap<>(LlmProvider.class);
    private final LlmProviderSelectionStore selectionStore;

    public LlmClientRegistry(
            @Qualifier("gemini") ObjectProvider<LlmClient> geminiProvider,
            @Qualifier("zhipu") ObjectProvider<LlmClient> zhipuProvider,
            LlmProviderSelectionStore selectionStore
    ) {
        this.selectionStore = selectionStore;

        LlmClient geminiClient = geminiProvider.getIfAvailable();
        if (geminiClient != null) {
            providerClients.put(LlmProvider.GEMINI, geminiClient);
        }

        LlmClient zhipuClient = zhipuProvider.getIfAvailable();
        if (zhipuClient != null) {
            providerClients.put(LlmProvider.ZHIPU, zhipuClient);
        }
    }

    public Optional<LlmClient> find(LlmProvider provider) {
        return Optional.ofNullable(providerClients.get(provider));
    }

    public LlmClient requireForTask(LlmTaskType taskType) {
        LlmProvider provider = resolveProviderForTask(taskType);
        LlmClient client = providerClients.get(provider);
        if (client == null) {
            throw new IllegalStateException("LLM provider is unavailable for task " + taskType + ": " + provider);
        }
        return client;
    }

    public LlmProvider resolveProviderForTask(LlmTaskType taskType) {
        LlmProvider selectedProvider = selectionStore.getSelection(taskType);
        if (isProviderAvailableForTask(selectedProvider, taskType)) {
            return selectedProvider;
        }

        LlmProvider fallbackProvider = pickFallbackProvider(taskType, selectedProvider);
        if (fallbackProvider != null) {
            log.warn("LLM provider {} unavailable for task {}. Falling back to {}.",
                    selectedProvider, taskType, fallbackProvider);
            return fallbackProvider;
        }

        throw new IllegalStateException("No available provider for task " + taskType
                + ". selected=" + selectedProvider + ", available=" + providerClients.keySet());
    }

    public boolean isTaskAvailable(LlmTaskType taskType) {
        LlmProvider selectedProvider = selectionStore.getSelection(taskType);
        if (isProviderAvailableForTask(selectedProvider, taskType)) {
            return true;
        }

        return pickFallbackProvider(taskType, selectedProvider) != null;
    }

    public boolean isProviderAvailableForTask(LlmProvider provider, LlmTaskType taskType) {
        if (provider == null) {
            return false;
        }
        if (!providerClients.containsKey(provider)) {
            return false;
        }
        return supportsTask(provider, taskType);
    }

    public List<LlmProviderCapability> describeCapabilities() {
        List<LlmProviderCapability> capabilities = new ArrayList<>();
        for (LlmProvider provider : LlmProvider.values()) {
            boolean available = providerClients.containsKey(provider);
            List<LlmTaskType> supportedTasks = new ArrayList<>(SUPPORTED_TASKS.getOrDefault(provider, EnumSet.noneOf(LlmTaskType.class)));
            List<LlmTaskType> degradedTasks = new ArrayList<>(DEGRADED_TASKS.getOrDefault(provider, EnumSet.noneOf(LlmTaskType.class)));

            capabilities.add(LlmProviderCapability.builder()
                    .provider(provider)
                    .displayName(displayName(provider))
                    .available(available)
                    .supportedTasks(supportedTasks)
                    .degradedTasks(degradedTasks)
                    .quotaStatus(LlmProviderCapability.LlmQuotaStatus.UNKNOWN)
                    .disabledReason(available ? null : "Provider is not configured.")
                    .build());
        }
        return capabilities;
    }

    private boolean supportsTask(LlmProvider provider, LlmTaskType taskType) {
        return SUPPORTED_TASKS.getOrDefault(provider, EnumSet.noneOf(LlmTaskType.class)).contains(taskType);
    }

    private LlmProvider pickFallbackProvider(LlmTaskType taskType, LlmProvider selectedProvider) {
        for (LlmProvider candidate : LlmProvider.values()) {
            if (candidate == selectedProvider) {
                continue;
            }
            if (isProviderAvailableForTask(candidate, taskType)) {
                return candidate;
            }
        }
        return null;
    }

    private String displayName(LlmProvider provider) {
        return switch (provider) {
            case GEMINI -> "Gemini";
            case ZHIPU -> "Zhipu";
        };
    }
}
