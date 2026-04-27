package com.whut.map.map_service.llm.prompt;

public enum PromptScene {
    CHAT("system-chat.txt"),
    RISK_EXPLANATION("system-risk-explanation.txt"),
    AGENT_CHAT("system-agent-chat.txt"),
    ADVISORY("system-advisory.txt"),
    RISK_EXPLANATION_USER_CONTEXT("user-risk-explanation-context.txt"),
    ADVISORY_USER_CONTEXT("user-advisory-context.txt");

    private final String resourceName;

    PromptScene(String resourceName) {
        this.resourceName = resourceName;
    }

    public String resourceName() {
        return resourceName;
    }
}
